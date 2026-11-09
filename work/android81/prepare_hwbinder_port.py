"""Prepare an experimental, isolated Binder backport for offline compilation.

No device installation is performed. Preserve the stock Binder implementation.
Upstream: LineageOS/android_kernel_samsung_msm8974,
390ab6ec6ff941f6407f91459612887faefa86e8 (GPL-2.0).
"""
from pathlib import Path
import re
import json

root = Path(__file__).parent
destination = root / 'hwbinder-module'
(destination / 'uapi').mkdir(parents=True, exist_ok=True)

def rename(text):
    text = text.replace('binder_', 'rayo_binder_')
    text = text.replace('security_rayo_binder_', 'security_binder_')
    text = text.replace('"binder.h"', '"rayo_binder.h"')
    text = text.replace('"uapi/binder.h"', '"uapi/rayo_binder.h"')
    return text

for source, target in (
    ('binder-samsung-oreo.h', 'rayo_binder.h'),
    ('binder-uapi-samsung-oreo.h', 'uapi/rayo_binder.h'),
    ('binder-trace-samsung-oreo.h', 'rayo_binder_trace.h'),
):
    text = rename((root / source).read_text())
    text = text.replace('#define TRACE_SYSTEM binder', '#define TRACE_SYSTEM rayo_hwbinder')
    (destination / target).write_text(text)

text = rename((root / 'binder-samsung-oreo.c').read_text())
text = text.replace('#include <linux/security.h>', '#include <linux/security.h>\n#include <linux/pid_namespace.h>')
# Use the 3.10 fd-table entry points, not the older copied 3.4 implementation.
# This driver holds a mutex, not Rockchip's preemption-disabled lock variant.
rk = rename((root / 'binder-rockchip-3.10.c').read_text())
fd_start = rk.index('static int task_get_unused_fd_flags(')
fd_end = rk.index('static inline void rayo_binder_lock(', fd_start)
helpers = rk[fd_start:fd_end]
helpers = helpers.replace('\tpreempt_enable_no_resched();\n', '').replace('\tpreempt_disable();\n', '')
start = text.index('static int task_get_unused_fd_flags(')
end = text.index('static void rayo_binder_set_nice(', start)
# Keep lock helpers if the upstream source places any between these functions.
old_helpers_end = text.index('static void rayo_binder_set_nice(', start)
lock_start = text.index('static inline void rayo_binder_lock(', start)
text = text[:start] + helpers + text[lock_start:]
# Equivalent core-dump guard introduced by later stable kernels; mmap_sem held.
text = text.replace('mmget_still_valid(mm)', '(!mm->core_state)')
text = text.replace('CONFIG_ANDROID_BINDER_DEVICES', '"hwbinder,vndbinder"')
text = text.replace('debugfs_create_dir("binder", NULL)', 'debugfs_create_dir("rayo_hwbinder", NULL)')
text = text.replace('uid_t rayo_binder_context_mgr_uid;', 'kuid_t rayo_binder_context_mgr_uid;')
text = text.replace('uid_t\tsender_euid;', 'kuid_t\tsender_euid;')
text = text.replace('tr.sender_euid = t->sender_euid;', 'tr.sender_euid = from_kuid(current_user_ns(), t->sender_euid);')
text = text.replace('context->rayo_binder_context_mgr_uid != -1', 'uid_valid(context->rayo_binder_context_mgr_uid)')
text = text.replace('context->rayo_binder_context_mgr_uid != current->cred->euid', '!uid_eq(context->rayo_binder_context_mgr_uid, current_euid())')
text = text.replace('current->cred->euid,\n', 'from_kuid(&init_user_ns, current_euid()),\n')
text = text.replace('context->rayo_binder_context_mgr_uid);', 'from_kuid(&init_user_ns, context->rayo_binder_context_mgr_uid));')
text = text.replace('context.rayo_binder_context_mgr_uid = -1;', 'context.rayo_binder_context_mgr_uid = INVALID_UID;')
text = text.replace('current->nsproxy->pid_ns', 'task_active_pid_ns(current)')
text = re.sub(r'hlist_for_each_entry\((\w+),\s*\w+,\s*([^,]+),\s*(\w+)\)', r'hlist_for_each_entry(\1, \2, \3)', text)
text = re.sub(r'hlist_for_each_entry_safe\((\w+),\s*\w+,\s*(\w+),\s*([^,]+),\s*(\w+)\)', r'hlist_for_each_entry_safe(\1, \2, \3, \4)', text)
text = text.replace('\tstruct hlist_node *pos;\n', '')
text = text.replace('struct hlist_node *node, *tmp;', 'struct hlist_node *tmp;')
layouts = json.loads((root / 'kernel-layouts-stock.json').read_text())
guard = ['/* Generated from the original drmboot.ko DWARF. */',
         'static inline void rayo_assert_stock_layouts(void)', '{']
guard_types = ('task_struct', 'mm_struct', 'vm_area_struct', 'file', 'inode', 'cred', 'page', 'thread_info', 'module', 'signal_struct', 'sighand_struct', 'nsproxy', 'vm_struct')
for name in guard_types:
    if name not in layouts:
        print('Stock DWARF did not emit a layout for:', name)
        continue
    item = layouts[name]
    guard.append(f'    BUILD_BUG_ON(sizeof(struct {name}) != {item["size"]});')
    for member, detail in item['members'].items():
        if detail['offset'] is not None and detail['bit_size'] is None:
            guard.append(f'    BUILD_BUG_ON(offsetof(struct {name}, {member}) != {detail["offset"]});')
guard.append('}')
(destination / 'rayo_layout_guard.h').write_text('\n'.join(guard) + '\n')
compat = (root / 'hwbinder_compat.c.inc').read_text()
text = text.replace('static HLIST_HEAD(rayo_binder_procs);', compat + '\nstatic HLIST_HEAD(rayo_binder_procs);', 1)
text = text.replace('rayo_binder_deferred_workqueue = create_singlethread_workqueue(',
                    'ret = rayo_resolve_originals();\n\tif (ret) return ret;\n\n\trayo_binder_deferred_workqueue = create_singlethread_workqueue(', 1)
(destination / 'rayo_hwbinder.c').write_text(text)
(destination / 'Makefile').write_text('obj-m += rayo_hwbinder.o\nccflags-y += -g -fno-pic -I$(src)\n')
print('Prepared experimental source:', destination)
