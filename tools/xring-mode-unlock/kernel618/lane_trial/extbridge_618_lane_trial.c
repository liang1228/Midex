// SPDX-License-Identifier: GPL-2.0
// Exact-build, one-shot DP pin-C trial. No persistent text or cache writes.
// The original vendor function performs ConfigureStart, both config stores,
// the PD Configure message, ACK notification, mux switching and HPD update.
// Kprobe register/PC semantics: https://docs.kernel.org/trace/kprobes.html
#include <linux/device.h>
#include <linux/delay.h>
#include <linux/kprobes.h>
#include <linux/module.h>
#include <linux/mutex.h>
#include <linux/string.h>
#include <linux/uaccess.h>
#include <linux/utsname.h>
#include <linux/workqueue.h>

#define CURRENT_RELEASE "6.18.21-android17-5-g284297031c53-abogki553682993-4k"
#define DEV_OFFSET 40
#define PD_OFFSET 8448
#define SELECT_OFFSET 0xf8
#define STORE_OFFSET 0x188
#define REQUEST_KCFI 0x9246b1a3

struct tcpc_device;
struct pd_port;
struct tcp_dpm_event_cb_data;
extern struct tcpc_device *tcpc_dev_get_by_name(const char *name);
extern int tcpm_dpm_dp_status_update(struct tcpc_device *port, u32 status,
		u32 mask, const struct tcp_dpm_event_cb_data *callback);

static struct tcpc_device *tcpc;
static struct pd_port *pd;
static struct device *dev;
static struct mutex *pd_lock;
static void (*request_config)(struct pd_port *);
static struct workqueue_struct *queue;
static struct work_struct begin_work;
static struct delayed_work restore_work;
static DEFINE_MUTEX(control);
static bool select_c, high_rate, pinned, hbr3, pin_c, nossc;
module_param(hbr3, bool, 0444);
module_param(pin_c, bool, 0444);
module_param(nossc, bool, 0444);
static unsigned int rate_overrides;
module_param(rate_overrides, uint, 0444);
static unsigned int phase, overrides, acknowledgements, last_ack;
static unsigned int local_config, remote_config, remote_status, dp_state, restore_attempts;
module_param(phase, uint, 0444); /* 0 ready, 1 starting, 2 C sent, 3 restored,
                                 4 disconnected, 5 restore pending, 6 refused */
module_param(overrides, uint, 0444);
module_param(acknowledgements, uint, 0444);
module_param(last_ack, uint, 0444);
module_param(local_config, uint, 0444);
module_param(remote_config, uint, 0444);
module_param(remote_status, uint, 0444);
module_param(dp_state, uint, 0444);
module_param(restore_attempts, uint, 0444);

static int select_pin(struct kprobe *probe, struct pt_regs *regs)
{
	if (READ_ONCE(select_c) && regs->regs[19] == (unsigned long)pd &&
	    (regs->regs[8] & BIT(2))) {
		/* Common capabilities were already filtered by the original code. */
		regs->regs[8] = 0x404;
		regs->pc = (unsigned long)probe->addr - SELECT_OFFSET + STORE_OFFSET;
		WRITE_ONCE(overrides, READ_ONCE(overrides) + 1);
		return 1;
	}
	return 0;
}
NOKPROBE_SYMBOL(select_pin);

static int select_rate(struct kprobe *probe, struct pt_regs *regs)
{
	/* Override only the source default; retain the sink min, lane cap,
	 * debug cap, PHY setup and the vendor's training/downgrade paths. */
	if (READ_ONCE(high_rate) && regs->regs[11] == 2 && regs->regs[12] == 3) {
		regs->regs[11] = 3;
		WRITE_ONCE(rate_overrides, READ_ONCE(rate_overrides) + 1);
	}
	return 0;
}
NOKPROBE_SYMBOL(select_rate);

static struct kprobe rate_probe = {
	.symbol_name = "xring_drm:dp_display_prepare_link_param",
	.offset = 0xb8, /* cmp w11,w12 at current .text+0x341a0 */
	.pre_handler = select_rate,
};

static int select_ssc(struct kprobe *probe, struct pt_regs *regs)
{
	if (READ_ONCE(high_rate) && nossc)
		regs->regs[11] = 0;
	return 0;
}
NOKPROBE_SYMBOL(select_ssc);

static struct kprobe ssc_probe = {
	.symbol_name = "xring_drm:dp_display_prepare_link_param",
	.offset = 0x54, /* and w9,w11,w10 at current .text+0x3413c */
	.pre_handler = select_ssc,
};

static int observe_ack(struct kprobe *probe, struct pt_regs *regs)
{
	if (regs->regs[0] == (unsigned long)tcpc) {
		WRITE_ONCE(last_ack, regs->regs[3] & 1);
		WRITE_ONCE(acknowledgements, READ_ONCE(acknowledgements) + 1);
	}
	return 0;
}
NOKPROBE_SYMBOL(observe_ack);

static struct kprobe selector = {
	.symbol_name = "external_tcpc:dp_dfp_u_request_dp_configuration",
	.offset = SELECT_OFFSET,
	.pre_handler = select_pin,
};
static struct kprobe ack_probe = {
	/* Cross-translation-unit call; the same-file notify wrapper is inlined. */
	.symbol_name = "external_tcpc:tcpci_dp_notify_config_done",
	.pre_handler = observe_ack,
};

/* Emitted KCFI entry must match the actual vendor function before any call. */
static noinline __used void signature_proof(struct pd_port *port)
{
	barrier_data(port);
}
static __used void (*volatile signature_reference)(struct pd_port *) = signature_proof;

static noinline __used int ack_signature_proof(struct tcpc_device *port,
		u32 local, u32 remote, bool ack)
{
	barrier_data(port);
	return (local ^ remote) + ack;
}
static __used int (*volatile ack_signature_reference)(struct tcpc_device *,
		u32, u32, bool) = ack_signature_proof;

/* Caller holds the real vendor pd_lock (tcpc+0x2108). */
static bool snapshot(void)
{
	u32 cache[4];
	u8 state;
	if (copy_from_kernel_nofault(cache, (char *)pd + 264, sizeof(cache)) ||
	    copy_from_kernel_nofault(&state, (char *)pd + 261, sizeof(state)))
		return false;
	WRITE_ONCE(local_config, cache[2]);
	WRITE_ONCE(remote_config, cache[3]);
	WRITE_ONCE(remote_status, cache[1]);
	WRITE_ONCE(dp_state, state);
	return true;
}

static bool partner_matches(void)
{
	void *entry;
	u32 local, remote;
	u16 svid;
	u8 active;
	if (copy_from_kernel_nofault(&entry, (char *)pd + 384, sizeof(entry)) ||
	    !entry ||
	    copy_from_kernel_nofault(&svid, (char *)entry + 2, sizeof(svid)) ||
	    copy_from_kernel_nofault(&active, (char *)entry + 4, sizeof(active)) ||
	    copy_from_kernel_nofault(&local, (char *)entry + 12, sizeof(local)) ||
	    copy_from_kernel_nofault(&remote, (char *)entry + 40, sizeof(remote)))
		return false;
	return svid == 0xff01 && active == 1 && local == 0x1c46 && remote == 0xc0045;
}

static void unpin(void)
{
	if (pinned) {
		pinned = false;
		module_put(THIS_MODULE);
	}
}

static void restore(struct work_struct *work)
{
	unsigned int before, i;
	bool disconnected, restored = false;

	mutex_lock(&control);
	WRITE_ONCE(high_rate, false);
	/* Query the partner instead of replaying a cached HPD IRQ as Plug-In. */
	tcpm_dpm_dp_status_update(tcpc, 0, 0, NULL);
	mutex_lock(pd_lock);
	if (!snapshot()) {
		mutex_unlock(pd_lock);
		goto retry;
	}
	disconnected = dp_state == 0 && remote_config == 0;
	if (disconnected) {
		phase = 4;
		mutex_unlock(pd_lock);
		goto done;
	}
	if (!partner_matches() || (dp_state != 7 && dp_state != 8)) {
		mutex_unlock(pd_lock);
		goto retry;
	}
	before = READ_ONCE(acknowledgements);
	WRITE_ONCE(select_c, false);
	if (restore_attempts >= 3) {
		mutex_unlock(pd_lock);
		goto retry;
	}
	++restore_attempts;
	request_config(pd);
	mutex_unlock(pd_lock);
	for (i = 0; i < 15; ++i) {
		msleep(200);
		mutex_lock(pd_lock);
		restored = snapshot() && dp_state == 8 && local_config == 0x805 &&
			remote_config == 0x806 && READ_ONCE(last_ack) &&
			READ_ONCE(acknowledgements) > before;
		mutex_unlock(pd_lock);
		if (restored)
			break;
	}
	if (restored) {
		phase = 3;
		pr_info("extbridge_618_lane_trial: original D restored, ACK observed\n");
		goto done;
	}
retry:
	phase = 5;
	/* Remain pinned until a real ACK or unplug. No forced module unload. */
	queue_delayed_work(queue, &restore_work, msecs_to_jiffies(3000));
	mutex_unlock(&control);
	return;
done:
	mutex_unlock(&control);
	unpin();
}

static void begin(struct work_struct *work)
{
	mutex_lock(&control);
	if (tcpm_dpm_dp_status_update(tcpc, 0, 0, NULL)) {
		phase = 6;
		mutex_unlock(&control);
		unpin();
		return;
	}
	mutex_lock(pd_lock);
	if (!snapshot() || !partner_matches() || dp_state != 8 ||
	    local_config != 0x805 || remote_config != 0x806) {
		phase = 6;
		mutex_unlock(pd_lock);
		mutex_unlock(&control);
		unpin();
		return;
	}
	/* Device-side restoration is armed before issuing the first command. */
	queue_delayed_work(queue, &restore_work, msecs_to_jiffies(45000));
	WRITE_ONCE(high_rate, hbr3);
	WRITE_ONCE(select_c, !hbr3 || pin_c);
	request_config(pd);
	WRITE_ONCE(select_c, false);
	snapshot();
	phase = 2;
	mutex_unlock(pd_lock);
	mutex_unlock(&control);
	pr_info("extbridge_618_lane_trial: trial requested hbr3=%u, automatic D restore armed\n", hbr3);
}

static int queue_operation(const char *value, bool test)
{
	bool start;
	int error = kstrtobool(value, &start);
	if (error || !start)
		return -EINVAL;
	if (!READ_ONCE(queue))
		return -EAGAIN;
	mutex_lock(&control);
	if (phase || !try_module_get(THIS_MODULE)) {
		mutex_unlock(&control);
		return -EBUSY;
	}
	pinned = true;
	phase = 1;
	if (test)
		queue_work(queue, &begin_work);
	else
		queue_delayed_work(queue, &restore_work, 0);
	mutex_unlock(&control);
	return 0;
}
static int start_trial(const char *value, const struct kernel_param *param)
{
	return queue_operation(value, true);
}
static int recover_link(const char *value, const struct kernel_param *param)
{
	return queue_operation(value, false);
}
static const struct kernel_param_ops start_ops = { .set = start_trial };
module_param_cb(trial, &start_ops, NULL, 0200);
static const struct kernel_param_ops recover_ops = { .set = recover_link };
module_param_cb(recover, &recover_ops, NULL, 0200);

static int read_status(char *buffer, const struct kernel_param *param)
{
	int length;
	mutex_lock(pd_lock);
	if (!snapshot()) {
		mutex_unlock(pd_lock);
		return -EIO;
	}
	length = scnprintf(buffer, PAGE_SIZE,
		"phase=%u state=%u local=0x%x remote=0x%x remote_status=0x%x overrides=%u acks=%u last_ack=%u restore_attempts=%u hbr3=%u rate_overrides=%u\n",
		READ_ONCE(phase), dp_state, local_config, remote_config,
		remote_status,
		READ_ONCE(overrides), READ_ONCE(acknowledgements),
		READ_ONCE(last_ack), READ_ONCE(restore_attempts), hbr3,
		READ_ONCE(rate_overrides));
	mutex_unlock(pd_lock);
	return length;
}
static const struct kernel_param_ops status_ops = { .get = read_status };
module_param_cb(status, &status_ops, NULL, 0444);

static int __init lane_init(void)
{
	u32 cfi, instruction;
	int error;
	if (strcmp(init_uts_ns.name.release, CURRENT_RELEASE))
		return -ENODEV;
	BUILD_BUG_ON(sizeof(struct mutex) != 40);
	BUILD_BUG_ON(offsetof(struct device, driver_data) != 152);
	tcpc = tcpc_dev_get_by_name("type_c_port0");
	if (!tcpc)
		return -ENODEV;
	dev = (void *)tcpc + DEV_OFFSET;
	pd = (void *)tcpc + PD_OFFSET;
	pd_lock = (void *)pd + 8;
	if (dev_get_drvdata(dev) != tcpc) {
		error = -EINVAL;
		goto put;
	}
	error = register_kprobe(&selector);
	if (error)
		goto put;
	request_config = (void *)selector.addr - SELECT_OFFSET;
	if (selector.opcode != 0x371803e8 ||
	    copy_from_kernel_nofault(&cfi, (void *)request_config - 4, sizeof(cfi)) ||
	    cfi != REQUEST_KCFI ||
	    copy_from_kernel_nofault(&instruction, (void *)request_config, sizeof(instruction)) ||
	    instruction != 0xd503233f) {
		error = -EINVAL;
		goto remove_selector;
	}
	error = register_kprobe(&ack_probe);
	if (error)
		goto remove_selector;
	if (hbr3) {
		error = register_kprobe(&rate_probe);
		if (error)
			goto remove_ack;
		if (rate_probe.opcode != 0x6b0c017f) {
			error = -EINVAL;
			goto remove_rate;
		}
	}
	if (hbr3 && nossc) {
		error = register_kprobe(&ssc_probe);
		if (error)
			goto remove_rate;
		if (ssc_probe.opcode != 0x0a0a0169) {
			error = -EINVAL;
			goto remove_ssc;
		}
	}
	queue = alloc_ordered_workqueue("extbridge_618_lane", WQ_MEM_RECLAIM);
	if (!queue) {
		error = -ENOMEM;
		goto remove_ssc;
	}
	INIT_WORK(&begin_work, begin);
	INIT_DELAYED_WORK(&restore_work, restore);
	mutex_lock(pd_lock);
	error = snapshot() && partner_matches() && dp_state == 8 &&
		local_config == 0x805 && remote_config == 0x806 ? 0 : -ENODEV;
	mutex_unlock(pd_lock);
	if (error) {
		destroy_workqueue(queue);
		goto remove_ssc;
	}
	pr_info("extbridge_618_lane_trial: ready; no PD command until trial=1\n");
	return 0;
remove_ssc:
	if (hbr3 && nossc)
		unregister_kprobe(&ssc_probe);
remove_rate:
	if (hbr3)
		unregister_kprobe(&rate_probe);
remove_ack:
	unregister_kprobe(&ack_probe);
remove_selector:
	unregister_kprobe(&selector);
put:
	put_device(dev);
	return error;
}

static void __exit lane_exit(void)
{
	cancel_work_sync(&begin_work);
	cancel_delayed_work_sync(&restore_work);
	destroy_workqueue(queue);
	if (hbr3 && nossc)
		unregister_kprobe(&ssc_probe);
	if (hbr3)
		unregister_kprobe(&rate_probe);
	unregister_kprobe(&ack_probe);
	unregister_kprobe(&selector);
	put_device(dev);
}
module_init(lane_init);
module_exit(lane_exit);
MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("Exact-build temporary DP pin-C trial with original-driver D restore");
MODULE_VERSION("0.1.0");
