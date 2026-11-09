/* Internal entry points retain their original permission/security checks.
 * Nothing is patched or exported into the shipping kernel. All addresses
 * must resolve before device registration; missing symbols reject loading.
 */
#include <linux/kallsyms.h>
#include "rayo_layout_guard.h"

static bool armed;
module_param(armed, bool, 0400);
MODULE_PARM_DESC(armed, "Explicit opt-in for a manually supervised experiment");

#define RAYO_FORWARD(result, name, params, args) \
    static result (*rayo_original_##name) params; \
    result name params { return rayo_original_##name args; }

RAYO_FORWARD(int, __close_fd, (struct files_struct *files, unsigned int fd), (files, fd))
RAYO_FORWARD(int, can_nice, (const struct task_struct *task, const int nice), (task, nice))
RAYO_FORWARD(void, zap_page_range, (struct vm_area_struct *vma, unsigned long address, unsigned long size, struct zap_details *details), (vma, address, size, details))
RAYO_FORWARD(void, unmap_kernel_range, (unsigned long address, unsigned long size), (address, size))
RAYO_FORWARD(struct vm_struct *, get_vm_area, (unsigned long size, unsigned long flags), (size, flags))
RAYO_FORWARD(struct files_struct *, get_files_struct, (struct task_struct *task), (task))
RAYO_FORWARD(struct sighand_struct *, __lock_task_sighand, (struct task_struct *task, unsigned long *flags), (task, flags))
RAYO_FORWARD(int, __alloc_fd, (struct files_struct *files, unsigned int start, unsigned int end, unsigned int flags), (files, start, end, flags))
RAYO_FORWARD(void, __fd_install, (struct files_struct *files, unsigned int fd, struct file *file), (files, fd, file))
RAYO_FORWARD(void, put_files_struct, (struct files_struct *files), (files))
RAYO_FORWARD(int, security_binder_transaction, (struct task_struct *from, struct task_struct *to), (from, to))
RAYO_FORWARD(int, security_binder_transfer_binder, (struct task_struct *from, struct task_struct *to), (from, to))
RAYO_FORWARD(int, security_binder_transfer_file, (struct task_struct *from, struct task_struct *to, struct file *file), (from, to, file))
RAYO_FORWARD(int, security_binder_set_context_mgr, (struct task_struct *manager), (manager))

static int rayo_resolve_originals(void)
{
    const char *banner;
    rayo_assert_stock_layouts();
    if (!armed) {
        pr_err("Refusing unattended load: armed=1 is required\n");
        return -EPERM;
    }
    banner = (const char *)kallsyms_lookup_name("linux_banner");
    if (!banner || strcmp(banner,
        "Linux version 3.10.104 (hotack@adminroot-X9DRL-3F-iF) "
        "(gcc version 4.6.x-google 20120106 (prerelease) (GCC) ) "
        "#241 SMP PREEMPT Fri Apr 26 18:51:18 CST 2019\n")) {
        pr_err("Refusing a kernel other than the backed-up Rayo unit\n");
        return -EINVAL;
    }
#define RAYO_RESOLVE(name) do { \
    rayo_original_##name = (typeof(rayo_original_##name))kallsyms_lookup_name(#name); \
    if (!rayo_original_##name) { pr_err("Missing kernel entry point: %s\n", #name); return -ENOENT; } \
} while (0)
    RAYO_RESOLVE(__close_fd);
    RAYO_RESOLVE(can_nice);
    RAYO_RESOLVE(zap_page_range);
    RAYO_RESOLVE(unmap_kernel_range);
    RAYO_RESOLVE(get_vm_area);
    RAYO_RESOLVE(get_files_struct);
    RAYO_RESOLVE(__lock_task_sighand);
    RAYO_RESOLVE(__alloc_fd);
    RAYO_RESOLVE(__fd_install);
    RAYO_RESOLVE(put_files_struct);
    RAYO_RESOLVE(security_binder_transaction);
    RAYO_RESOLVE(security_binder_transfer_binder);
    RAYO_RESOLVE(security_binder_transfer_file);
    RAYO_RESOLVE(security_binder_set_context_mgr);
#undef RAYO_RESOLVE
    return 0;
}
