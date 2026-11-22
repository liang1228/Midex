// SPDX-License-Identifier: GPL-2.0
// Exact-current binary diagnostic. Kprobes API: docs.kernel.org/trace/kprobes.html
#include <linux/kprobes.h>
#include <linux/module.h>
#include <linux/string.h>
#include <linux/utsname.h>
#include <linux/workqueue.h>

#define CURRENT_RELEASE "6.18.21-android17-5-g284297031c53-abogki553682993-4k"

static bool enabled = true;
static unsigned long overrides;
module_param(enabled, bool, 0444);
module_param(overrides, ulong, 0444);

static int allow_165(struct kprobe *probe, struct pt_regs *regs)
{
	const void *mode = (void *)regs->regs[1];
	// The original function has already read this live drm_display_mode.
	if (READ_ONCE(enabled) && (u32)regs->regs[9] == 699500 &&
	    (u32)regs->regs[10] == 594000 &&
	    READ_ONCE(*(const u16 *)(mode + 4)) == 2560 &&
	    READ_ONCE(*(const u16 *)(mode + 14)) == 1440) {
		// Preserve the following real bandwidth/DSC check and all timing data.
		regs->regs[10] = 699500;
		WRITE_ONCE(overrides, READ_ONCE(overrides) + 1);
	}
	return 0;
}
NOKPROBE_SYMBOL(allow_165);

static struct kprobe cap_probe = {
	.symbol_name = "xring_drm:dp_connector_mode_valid",
	.offset = 0x1f8,
	.pre_handler = allow_165,
};

static void expire(struct work_struct *work)
{
	WRITE_ONCE(enabled, false);
	pr_info("extbridge_618_mode_cap: eligibility override expired; overrides=%lu\n",
		READ_ONCE(overrides));
}
static DECLARE_DELAYED_WORK(expiry, expire);

static int __init mode_cap_init(void)
{
	int error;
	if (strcmp(init_uts_ns.name.release, CURRENT_RELEASE))
		return -ENODEV;
	error = register_kprobe(&cap_probe);
	if (error)
		return error;
	if (cap_probe.opcode != 0x6b0a013f) {
		unregister_kprobe(&cap_probe);
		return -EINVAL;
	}
	// HAL trial has its own earlier 45-second physical-mode restoration.
	schedule_delayed_work(&expiry, 90 * HZ);
	pr_info("extbridge_618_mode_cap: exact 2K699500 eligibility only; expires in 90s\n");
	return 0;
}

static void __exit mode_cap_exit(void)
{
	WRITE_ONCE(enabled, false);
	cancel_delayed_work_sync(&expiry);
	unregister_kprobe(&cap_probe);
}
module_init(mode_cap_init);
module_exit(mode_cap_exit);
MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("Temporary exact-2K165 eligibility test; does not set modes or link parameters");
MODULE_VERSION("0.1.0");
