// SPDX-License-Identifier: GPL-2.0
// Read-only ABI/load proof for violin's current Android 17 / 6.18 kernel.
// Kprobe lifecycle: https://docs.kernel.org/trace/kprobes.html
#include <linux/kprobes.h>
#include <linux/module.h>
#include <linux/string.h>
#include <linux/uaccess.h>
#include <linux/utsname.h>

#define CURRENT_RELEASE "6.18.21-android17-5-g284297031c53-abogki553682993-4k"
#define TCPC_DEV_OFFSET 40
#define TCPC_PD_OFFSET 8448

static unsigned long observations;
static unsigned int local_status, remote_status, local_config, remote_config;
module_param(observations, ulong, 0444);
module_param(local_status, uint, 0444);
module_param(remote_status, uint, 0444);
module_param(local_config, uint, 0444);
module_param(remote_config, uint, 0444);

static int observe(struct kprobe *probe, struct pt_regs *regs)
{
	u32 cache[4];
	unsigned long pd = regs->regs[0] - TCPC_DEV_OFFSET + TCPC_PD_OFFSET;

	if (!copy_from_kernel_nofault(cache, (void *)(pd + 264), sizeof(cache))) {
		WRITE_ONCE(local_status, cache[0]);
		WRITE_ONCE(remote_status, cache[1]);
		WRITE_ONCE(local_config, cache[2]);
		WRITE_ONCE(remote_config, cache[3]);
		WRITE_ONCE(observations, READ_ONCE(observations) + 1);
	}
	return 0;
}
NOKPROBE_SYMBOL(observe);

static struct kprobe observation = {
	.symbol_name = "external_tcpc:tcpc_show_property",
	.pre_handler = observe,
};

static int __init observer_init(void)
{
	int error;

	if (strcmp(init_uts_ns.name.release, CURRENT_RELEASE))
		return -ENODEV;
	error = register_kprobe(&observation);
	if (error)
		return error;
	pr_info("extbridge_618_observer: read-only loaded module=%zu kprobe=%zu regs=%zu\n",
		sizeof(struct module), sizeof(struct kprobe), sizeof(struct pt_regs));
	return 0;
}

static void __exit observer_exit(void)
{
	unregister_kprobe(&observation);
	pr_info("extbridge_618_observer: removed observations=%lu\n", observations);
}

module_init(observer_init);
module_exit(observer_exit);
MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("Read-only current-kernel PD cache observer; no display or PD writes");
MODULE_VERSION("0.1.0");
