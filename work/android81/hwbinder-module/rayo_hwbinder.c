/* binder.c
 *
 * Android IPC Subsystem
 *
 * Copyright (C) 2007-2008 Google, Inc.
 *
 * This software is licensed under the terms of the GNU General Public
 * License version 2, as published by the Free Software Foundation, and
 * may be copied, distributed, and modified under those terms.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 */

#define pr_fmt(fmt) KBUILD_MODNAME ": " fmt

#include <asm/cacheflush.h>
#include <linux/fdtable.h>
#include <linux/file.h>
#include <linux/freezer.h>
#include <linux/fs.h>
#include <linux/list.h>
#include <linux/miscdevice.h>
#include <linux/mm.h>
#include <linux/module.h>
#include <linux/mutex.h>
#include <linux/nsproxy.h>
#include <linux/poll.h>
#include <linux/debugfs.h>
#include <linux/rbtree.h>
#include <linux/sched.h>
#include <linux/seq_file.h>
#include <linux/uaccess.h>
#include <linux/vmalloc.h>
#include <linux/slab.h>
#include <linux/security.h>
#include <linux/pid_namespace.h>

#include "rayo_binder.h"
#include "rayo_binder_trace.h"

#define BINDER_MIN_ALLOC (1 * PAGE_SIZE)

static DEFINE_MUTEX(rayo_binder_main_lock);
static DEFINE_MUTEX(rayo_binder_deferred_lock);
static DEFINE_MUTEX(rayo_binder_mmap_lock);

static HLIST_HEAD(rayo_binder_devices);
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

static HLIST_HEAD(rayo_binder_procs);
static HLIST_HEAD(rayo_binder_deferred_list);
static HLIST_HEAD(rayo_binder_dead_nodes);

static struct dentry *rayo_binder_debugfs_dir_entry_root;
static struct dentry *rayo_binder_debugfs_dir_entry_proc;
static int rayo_binder_last_id;
static struct workqueue_struct *rayo_binder_deferred_workqueue;

#define BINDER_DEBUG_ENTRY(name) \
static int rayo_binder_##name##_open(struct inode *inode, struct file *file) \
{ \
	return single_open(file, rayo_binder_##name##_show, inode->i_private); \
} \
\
static const struct file_operations rayo_binder_##name##_fops = { \
	.owner = THIS_MODULE, \
	.open = rayo_binder_##name##_open, \
	.read = seq_read, \
	.llseek = seq_lseek, \
	.release = single_release, \
}

static int rayo_binder_proc_show(struct seq_file *m, void *unused);
BINDER_DEBUG_ENTRY(proc);

/* This is only defined in include/asm-arm/sizes.h */
#ifndef SZ_1K
#define SZ_1K                               0x400
#endif

#ifndef SZ_4M
#define SZ_4M                               0x400000
#endif

#define FORBIDDEN_MMAP_FLAGS                (VM_WRITE)

#define BINDER_SMALL_BUF_SIZE (PAGE_SIZE * 64)

enum {
	BINDER_DEBUG_USER_ERROR             = 1U << 0,
	BINDER_DEBUG_FAILED_TRANSACTION     = 1U << 1,
	BINDER_DEBUG_DEAD_TRANSACTION       = 1U << 2,
	BINDER_DEBUG_OPEN_CLOSE             = 1U << 3,
	BINDER_DEBUG_DEAD_BINDER            = 1U << 4,
	BINDER_DEBUG_DEATH_NOTIFICATION     = 1U << 5,
	BINDER_DEBUG_READ_WRITE             = 1U << 6,
	BINDER_DEBUG_USER_REFS              = 1U << 7,
	BINDER_DEBUG_THREADS                = 1U << 8,
	BINDER_DEBUG_TRANSACTION            = 1U << 9,
	BINDER_DEBUG_TRANSACTION_COMPLETE   = 1U << 10,
	BINDER_DEBUG_FREE_BUFFER            = 1U << 11,
	BINDER_DEBUG_INTERNAL_REFS          = 1U << 12,
	BINDER_DEBUG_BUFFER_ALLOC           = 1U << 13,
	BINDER_DEBUG_PRIORITY_CAP           = 1U << 14,
	BINDER_DEBUG_BUFFER_ALLOC_ASYNC     = 1U << 15,
	BINDER_DEBUG_TOP_ERRORS		    = 1U << 16,
};
static uint32_t rayo_binder_debug_mask;
module_param_named(debug_mask, rayo_binder_debug_mask, uint, S_IWUSR | S_IRUGO);

static bool rayo_binder_debug_no_lock;
module_param_named(proc_no_lock, rayo_binder_debug_no_lock, bool, S_IWUSR | S_IRUGO);

static char *rayo_binder_devices_param = "hwbinder,vndbinder";
module_param_named(devices, rayo_binder_devices_param, charp, S_IRUGO);

static DECLARE_WAIT_QUEUE_HEAD(rayo_binder_user_error_wait);
static int rayo_binder_stop_on_user_error;

static int rayo_binder_set_stop_on_user_error(const char *val,
					 struct kernel_param *kp)
{
	int ret;

	ret = param_set_int(val, kp);
	if (rayo_binder_stop_on_user_error < 2)
		wake_up(&rayo_binder_user_error_wait);
	return ret;
}
module_param_call(stop_on_user_error, rayo_binder_set_stop_on_user_error,
	param_get_int, &rayo_binder_stop_on_user_error, S_IWUSR | S_IRUGO);

#define rayo_binder_debug(mask, x...) \
	do { \
		if (rayo_binder_debug_mask & mask) \
			pr_info(x); \
	} while (0)

#define rayo_binder_user_error(x...) \
	do { \
		if (rayo_binder_debug_mask & BINDER_DEBUG_USER_ERROR) \
			pr_info(x); \
		if (rayo_binder_stop_on_user_error) \
			rayo_binder_stop_on_user_error = 2; \
	} while (0)

#define to_flat_rayo_binder_object(hdr) \
	container_of(hdr, struct flat_rayo_binder_object, hdr)

#define to_rayo_binder_fd_object(hdr) container_of(hdr, struct rayo_binder_fd_object, hdr)

#define to_rayo_binder_buffer_object(hdr) \
	container_of(hdr, struct rayo_binder_buffer_object, hdr)

#define to_rayo_binder_fd_array_object(hdr) \
	container_of(hdr, struct rayo_binder_fd_array_object, hdr)

enum rayo_binder_stat_types {
	BINDER_STAT_PROC,
	BINDER_STAT_THREAD,
	BINDER_STAT_NODE,
	BINDER_STAT_REF,
	BINDER_STAT_DEATH,
	BINDER_STAT_TRANSACTION,
	BINDER_STAT_TRANSACTION_COMPLETE,
	BINDER_STAT_COUNT
};

struct rayo_binder_stats {
	int br[_IOC_NR(BR_FAILED_REPLY) + 1];
	int bc[_IOC_NR(BC_REPLY_SG) + 1];
	int obj_created[BINDER_STAT_COUNT];
	int obj_deleted[BINDER_STAT_COUNT];
};

static struct rayo_binder_stats rayo_binder_stats;

static inline void rayo_binder_stats_deleted(enum rayo_binder_stat_types type)
{
	rayo_binder_stats.obj_deleted[type]++;
}

static inline void rayo_binder_stats_created(enum rayo_binder_stat_types type)
{
	rayo_binder_stats.obj_created[type]++;
}

struct rayo_binder_transaction_log_entry {
	int debug_id;
	int call_type;
	int from_proc;
	int from_thread;
	int target_handle;
	int to_proc;
	int to_thread;
	int to_node;
	int data_size;
	int offsets_size;
	const char *context_name;
};
struct rayo_binder_transaction_log {
	int next;
	int full;
	struct rayo_binder_transaction_log_entry entry[32];
};
static struct rayo_binder_transaction_log rayo_binder_transaction_log;
static struct rayo_binder_transaction_log rayo_binder_transaction_log_failed;

static struct rayo_binder_transaction_log_entry *rayo_binder_transaction_log_add(
	struct rayo_binder_transaction_log *log)
{
	struct rayo_binder_transaction_log_entry *e;

	e = &log->entry[log->next];
	memset(e, 0, sizeof(*e));
	log->next++;
	if (log->next == ARRAY_SIZE(log->entry)) {
		log->next = 0;
		log->full = 1;
	}
	return e;
}

struct rayo_binder_context {
	struct rayo_binder_node *rayo_binder_context_mgr_node;
	kuid_t rayo_binder_context_mgr_uid;
	const char *name;
};

struct rayo_binder_device {
	struct hlist_node hlist;
	struct miscdevice miscdev;
	struct rayo_binder_context context;
};

struct rayo_binder_work {
	struct list_head entry;
	enum {
		BINDER_WORK_TRANSACTION = 1,
		BINDER_WORK_TRANSACTION_COMPLETE,
		BINDER_WORK_NODE,
		BINDER_WORK_DEAD_BINDER,
		BINDER_WORK_DEAD_BINDER_AND_CLEAR,
		BINDER_WORK_CLEAR_DEATH_NOTIFICATION,
	} type;
};

struct rayo_binder_node {
	int debug_id;
	struct rayo_binder_work work;
	union {
		struct rb_node rb_node;
		struct hlist_node dead_node;
	};
	struct rayo_binder_proc *proc;
	struct hlist_head refs;
	int internal_strong_refs;
	int local_weak_refs;
	int local_strong_refs;
	rayo_binder_uintptr_t ptr;
	rayo_binder_uintptr_t cookie;
	unsigned has_strong_ref:1;
	unsigned pending_strong_ref:1;
	unsigned has_weak_ref:1;
	unsigned pending_weak_ref:1;
	unsigned has_async_transaction:1;
	unsigned accept_fds:1;
	unsigned min_priority:8;
	struct list_head async_todo;
};

struct rayo_binder_ref_death {
	struct rayo_binder_work work;
	rayo_binder_uintptr_t cookie;
};

struct rayo_binder_ref {
	/* Lookups needed: */
	/*   node + proc => ref (transaction) */
	/*   desc + proc => ref (transaction, inc/dec ref) */
	/*   node => refs + procs (proc exit) */
	int debug_id;
	struct rb_node rb_node_desc;
	struct rb_node rb_node_node;
	struct hlist_node node_entry;
	struct rayo_binder_proc *proc;
	struct rayo_binder_node *node;
	uint32_t desc;
	int strong;
	int weak;
	struct rayo_binder_ref_death *death;
};

struct rayo_binder_buffer {
	struct list_head entry; /* free and allocated entries by address */
	struct rb_node rb_node; /* free entry by size or allocated entry */
				/* by address */
	unsigned free:1;
	unsigned allow_user_free:1;
	unsigned async_transaction:1;
	unsigned debug_id:29;

	struct rayo_binder_transaction *transaction;

	struct rayo_binder_node *target_node;
	size_t data_size;
	size_t offsets_size;
	size_t extra_buffers_size;
	void *data;
};

enum rayo_binder_deferred_state {
	BINDER_DEFERRED_PUT_FILES    = 0x01,
	BINDER_DEFERRED_FLUSH        = 0x02,
	BINDER_DEFERRED_RELEASE      = 0x04,
};

struct rayo_binder_proc {
	struct hlist_node proc_node;
	struct rb_root threads;
	struct rb_root nodes;
	struct rb_root refs_by_desc;
	struct rb_root refs_by_node;
	int pid;
	struct vm_area_struct *vma;
	struct mm_struct *vma_vm_mm;
	struct task_struct *tsk;
	struct files_struct *files;
	struct hlist_node deferred_work_node;
	int deferred_work;
	void *buffer;
	ptrdiff_t user_buffer_offset;

	struct list_head buffers;
	struct rb_root free_buffers;
	struct rb_root allocated_buffers;
	size_t free_async_space;

	struct page **pages;
	size_t buffer_size;
	uint32_t buffer_free;
	struct list_head todo;
	wait_queue_head_t wait;
	struct rayo_binder_stats stats;
	struct list_head delivered_death;
	int max_threads;
	int requested_threads;
	int requested_threads_started;
	int ready_threads;
	long default_priority;
	struct dentry *debugfs_entry;
	struct rayo_binder_context *context;
};

enum {
	BINDER_LOOPER_STATE_REGISTERED  = 0x01,
	BINDER_LOOPER_STATE_ENTERED     = 0x02,
	BINDER_LOOPER_STATE_EXITED      = 0x04,
	BINDER_LOOPER_STATE_INVALID     = 0x08,
	BINDER_LOOPER_STATE_WAITING     = 0x10,
	BINDER_LOOPER_STATE_NEED_RETURN = 0x20
};

struct rayo_binder_thread {
	struct rayo_binder_proc *proc;
	struct rb_node rb_node;
	int pid;
	int looper;
	struct rayo_binder_transaction *transaction_stack;
	struct list_head todo;
	uint32_t return_error; /* Write failed, return error code in read buf */
	uint32_t return_error2; /* Write failed, return error code in read */
		/* buffer. Used when sending a reply to a dead process that */
		/* we are also waiting on */
	wait_queue_head_t wait;
	struct rayo_binder_stats stats;
};

struct rayo_binder_transaction {
	int debug_id;
	struct rayo_binder_work work;
	struct rayo_binder_thread *from;
	struct rayo_binder_transaction *from_parent;
	struct rayo_binder_proc *to_proc;
	struct rayo_binder_thread *to_thread;
	struct rayo_binder_transaction *to_parent;
	unsigned need_reply:1;
	/* unsigned is_dead:1; */	/* not used at the moment */

	struct rayo_binder_buffer *buffer;
	unsigned int	code;
	unsigned int	flags;
	long	priority;
	long	saved_priority;
	kuid_t	sender_euid;
};

static void
rayo_binder_defer_work(struct rayo_binder_proc *proc, enum rayo_binder_deferred_state defer);

/*
 * copied from get_unused_fd_flags
 */
static int task_get_unused_fd_flags(struct rayo_binder_proc *proc, int flags)
{
	struct files_struct *files = proc->files;
	unsigned long rlim_cur;
	unsigned long irqs;
	int ret;

	if (files == NULL)
		return -ESRCH;

	if (!lock_task_sighand(proc->tsk, &irqs))
		return -EMFILE;

	rlim_cur = task_rlimit(proc->tsk, RLIMIT_NOFILE);
	unlock_task_sighand(proc->tsk, &irqs);

	ret = __alloc_fd(files, 0, rlim_cur, flags);

	return ret;
}

/*
 * copied from fd_install
 */
static void task_fd_install(
	struct rayo_binder_proc *proc, unsigned int fd, struct file *file)
{
	if (proc->files) {
			__fd_install(proc->files, fd, file);
		}
}

/*
 * copied from sys_close
 */
static long task_close_fd(struct rayo_binder_proc *proc, unsigned int fd)
{
	int retval;

	if (proc->files == NULL)
		return -ESRCH;

	retval = __close_fd(proc->files, fd);
	/* can't restart close syscall because file table entry was cleared */
	if (unlikely(retval == -ERESTARTSYS ||
		     retval == -ERESTARTNOINTR ||
		     retval == -ERESTARTNOHAND ||
		     retval == -ERESTART_RESTARTBLOCK))
		retval = -EINTR;

	return retval;
}

static inline void rayo_binder_lock(const char *tag)
{
	trace_rayo_binder_lock(tag);
	mutex_lock(&rayo_binder_main_lock);
	trace_rayo_binder_locked(tag);
}

static inline void rayo_binder_unlock(const char *tag)
{
	trace_rayo_binder_unlock(tag);
	mutex_unlock(&rayo_binder_main_lock);
}

static void rayo_binder_set_nice(long nice)
{
	long min_nice;

	if (can_nice(current, nice)) {
		set_user_nice(current, nice);
		return;
	}
	min_nice = 20 - current->signal->rlim[RLIMIT_NICE].rlim_cur;
	rayo_binder_debug(BINDER_DEBUG_PRIORITY_CAP,
		     "%d: nice value %ld not allowed use %ld instead\n",
		      current->pid, nice, min_nice);
	set_user_nice(current, min_nice);
	if (min_nice < 20)
		return;
	rayo_binder_user_error("%d RLIMIT_NICE not set\n", current->pid);
}

static struct rayo_binder_buffer *rayo_binder_buffer_next(struct rayo_binder_buffer *buffer)
{
	return list_entry(buffer->entry.next, struct rayo_binder_buffer, entry);
}

static struct rayo_binder_buffer *rayo_binder_buffer_prev(struct rayo_binder_buffer *buffer)
{
	return list_entry(buffer->entry.prev, struct rayo_binder_buffer, entry);
}

static size_t rayo_binder_buffer_size(struct rayo_binder_proc *proc,
				 struct rayo_binder_buffer *buffer)
{
	if (list_is_last(&buffer->entry, &proc->buffers))
		return (u8 *)proc->buffer +
			proc->buffer_size - (u8 *)buffer->data;
	return (u8 *)rayo_binder_buffer_next(buffer)->data - (u8 *)buffer->data;
}

static void rayo_binder_insert_free_buffer(struct rayo_binder_proc *proc,
				      struct rayo_binder_buffer *new_buffer)
{
	struct rb_node **p = &proc->free_buffers.rb_node;
	struct rb_node *parent = NULL;
	struct rayo_binder_buffer *buffer;
	size_t buffer_size;
	size_t new_buffer_size;

	BUG_ON(!new_buffer->free);

	new_buffer_size = rayo_binder_buffer_size(proc, new_buffer);

	rayo_binder_debug(BINDER_DEBUG_BUFFER_ALLOC,
		     "%d: add free buffer, size %zd, at %pK\n",
		      proc->pid, new_buffer_size, new_buffer);

	while (*p) {
		parent = *p;
		buffer = rb_entry(parent, struct rayo_binder_buffer, rb_node);
		BUG_ON(!buffer->free);

		buffer_size = rayo_binder_buffer_size(proc, buffer);

		if (new_buffer_size < buffer_size)
			p = &parent->rb_left;
		else
			p = &parent->rb_right;
	}
	rb_link_node(&new_buffer->rb_node, parent, p);
	rb_insert_color(&new_buffer->rb_node, &proc->free_buffers);
}

static void rayo_binder_insert_allocated_buffer(struct rayo_binder_proc *proc,
					   struct rayo_binder_buffer *new_buffer)
{
	struct rb_node **p = &proc->allocated_buffers.rb_node;
	struct rb_node *parent = NULL;
	struct rayo_binder_buffer *buffer;

	BUG_ON(new_buffer->free);

	while (*p) {
		parent = *p;
		buffer = rb_entry(parent, struct rayo_binder_buffer, rb_node);
		BUG_ON(buffer->free);

		if (new_buffer->data < buffer->data)
			p = &parent->rb_left;
		else if (new_buffer->data > buffer->data)
			p = &parent->rb_right;
		else
			BUG();
	}
	rb_link_node(&new_buffer->rb_node, parent, p);
	rb_insert_color(&new_buffer->rb_node, &proc->allocated_buffers);
}

static struct rayo_binder_buffer *rayo_binder_buffer_lookup(struct rayo_binder_proc *proc,
						  uintptr_t user_ptr)
{
	struct rb_node *n = proc->allocated_buffers.rb_node;
	struct rayo_binder_buffer *buffer;
	void *kern_ptr;

	kern_ptr = (void *)(user_ptr - proc->user_buffer_offset);

	while (n) {
		buffer = rb_entry(n, struct rayo_binder_buffer, rb_node);
		BUG_ON(buffer->free);

		if (kern_ptr < buffer->data)
			n = n->rb_left;
		else if (kern_ptr > buffer->data)
			n = n->rb_right;
		else
			return buffer;
	}
	return NULL;
}

static int __rayo_binder_update_page_range(struct rayo_binder_proc *proc, int allocate,
				      void *start, void *end,
				      struct vm_area_struct *vma)
{
	void *page_addr;
	unsigned long user_page_addr;
	struct vm_struct tmp_area;
	struct page **page;
	struct mm_struct *mm;

	rayo_binder_debug(BINDER_DEBUG_BUFFER_ALLOC,
		     "%d: %s pages %pK-%pK\n", proc->pid,
		     allocate ? "allocate" : "free", start, end);

	if (end <= start)
		return 0;

	trace_rayo_binder_update_page_range(proc, allocate, start, end);

	if (vma)
		mm = NULL;
	else
		mm = get_task_mm(proc->tsk);

	if (mm) {
		down_write(&mm->mmap_sem);
		if (!(!mm->core_state)) {
			if (allocate == 0)
				goto free_range;
			goto err_no_vma;
		}
		vma = proc->vma;
		if (vma && mm != proc->vma_vm_mm) {
			pr_err("%d: vma mm and task mm mismatch\n",
				proc->pid);
			vma = NULL;
		}
	}

	if (allocate == 0)
		goto free_range;

	if (vma == NULL) {
		rayo_binder_debug(BINDER_DEBUG_TOP_ERRORS,
			     "%d: rayo_binder_alloc_buf failed to map pages in userspace, no vma\n",
			     proc->pid);
		goto err_no_vma;
	}

	for (page_addr = start; page_addr < end; page_addr += PAGE_SIZE) {
		int ret;
		struct page **page_array_ptr;

		page = &proc->pages[(page_addr - proc->buffer) / PAGE_SIZE];

		BUG_ON(*page);
		*page = alloc_page(GFP_KERNEL | __GFP_HIGHMEM | __GFP_ZERO);
		if (*page == NULL) {
			rayo_binder_debug(BINDER_DEBUG_TOP_ERRORS,
				     "%d: rayo_binder_alloc_buf failed for page at %pK\n",
				     proc->pid, page_addr);
			goto err_alloc_page_failed;
		}
		tmp_area.addr = page_addr;
		tmp_area.size = PAGE_SIZE + PAGE_SIZE /* guard page? */;
		page_array_ptr = page;
		ret = map_vm_area(&tmp_area, PAGE_KERNEL, &page_array_ptr);
		if (ret) {
			rayo_binder_debug(BINDER_DEBUG_TOP_ERRORS,
				     "%d: rayo_binder_alloc_buf failed to map page at %pK in kernel\n",
				     proc->pid, page_addr);
			goto err_map_kernel_failed;
		}
		user_page_addr =
			(uintptr_t)page_addr + proc->user_buffer_offset;
		ret = vm_insert_page(vma, user_page_addr, page[0]);
		if (ret) {
			rayo_binder_debug(BINDER_DEBUG_TOP_ERRORS,
				     "%d: rayo_binder_alloc_buf failed to map page at %lx in userspace\n",
				     proc->pid, user_page_addr);
			goto err_vm_insert_page_failed;
		}
		/* vm_insert_page does not seem to increment the refcount */
	}
	if (mm) {
		up_write(&mm->mmap_sem);
		mmput(mm);
	}
	return 0;

free_range:
	for (page_addr = end - PAGE_SIZE; page_addr >= start;
	     page_addr -= PAGE_SIZE) {
		page = &proc->pages[(page_addr - proc->buffer) / PAGE_SIZE];
		if (vma)
			zap_page_range(vma, (uintptr_t)page_addr +
				proc->user_buffer_offset, PAGE_SIZE, NULL);
err_vm_insert_page_failed:
		unmap_kernel_range((unsigned long)page_addr, PAGE_SIZE);
err_map_kernel_failed:
		__free_page(*page);
		*page = NULL;
err_alloc_page_failed:
		;
	}
err_no_vma:
	if (mm) {
		up_write(&mm->mmap_sem);
		mmput(mm);
	}
	return -ENOMEM;
}

static int rayo_binder_update_page_range(struct rayo_binder_proc *proc, int allocate,
				    void *start, void *end,
				    struct vm_area_struct *vma)
{
	/*
	 * For regular updates, move up start if needed since MIN_ALLOC pages
	 * are always mapped
	 */
	if (start - proc->buffer < BINDER_MIN_ALLOC)
		start = proc->buffer + BINDER_MIN_ALLOC;

	return __rayo_binder_update_page_range(proc, allocate, start, end, vma);
}

static struct rayo_binder_buffer *rayo_binder_alloc_buf(struct rayo_binder_proc *proc,
					      size_t data_size,
					      size_t offsets_size,
					      size_t extra_buffers_size,
					      int is_async)
{
	struct rb_node *n = proc->free_buffers.rb_node;
	struct rayo_binder_buffer *buffer;
	size_t buffer_size;
	struct rb_node *best_fit = NULL;
	void *has_page_addr;
	void *end_page_addr;
	size_t size, data_offsets_size;

	if (proc->vma == NULL) {
		rayo_binder_debug(BINDER_DEBUG_TOP_ERRORS,
			     "%d: rayo_binder_alloc_buf, no vma\n",
			     proc->pid);
		return NULL;
	}

	data_offsets_size = ALIGN(data_size, sizeof(void *)) +
		ALIGN(offsets_size, sizeof(void *));

	if (data_offsets_size < data_size || data_offsets_size < offsets_size) {
		rayo_binder_user_error("%d: got transaction with invalid size %zd-%zd\n",
				proc->pid, data_size, offsets_size);
		return NULL;
	}
	size = data_offsets_size + ALIGN(extra_buffers_size, sizeof(void *));
	if (size < data_offsets_size || size < extra_buffers_size) {
		rayo_binder_user_error("%d: got transaction with invalid extra_buffers_size %zd\n",
				  proc->pid, extra_buffers_size);
		return NULL;
	}
	if (is_async &&
	    proc->free_async_space < size + sizeof(struct rayo_binder_buffer)) {
		rayo_binder_debug(BINDER_DEBUG_BUFFER_ALLOC,
			     "%d: rayo_binder_alloc_buf size %zd failed, no async space left\n",
			      proc->pid, size);
		return NULL;
	}

	/* Pad 0-size buffers so they get assigned unique addresses */
	size = max(size, sizeof(void *));

	while (n) {
		buffer = rb_entry(n, struct rayo_binder_buffer, rb_node);
		BUG_ON(!buffer->free);
		buffer_size = rayo_binder_buffer_size(proc, buffer);

		if (size < buffer_size) {
			best_fit = n;
			n = n->rb_left;
		} else if (size > buffer_size)
			n = n->rb_right;
		else {
			best_fit = n;
			break;
		}
	}
	if (best_fit == NULL) {
		rayo_binder_debug(BINDER_DEBUG_TOP_ERRORS,
			     "%d: rayo_binder_alloc_buf size %zd failed, no address space\n",
			     proc->pid, size);
		return NULL;
	}
	if (n == NULL) {
		buffer = rb_entry(best_fit, struct rayo_binder_buffer, rb_node);
		buffer_size = rayo_binder_buffer_size(proc, buffer);
	}

	rayo_binder_debug(BINDER_DEBUG_BUFFER_ALLOC,
		     "%d: rayo_binder_alloc_buf size %zd got buffer %pK size %zd\n",
		      proc->pid, size, buffer, buffer_size);

	has_page_addr =
		(void *)(((uintptr_t)buffer->data + buffer_size) & PAGE_MASK);
	WARN_ON(n && buffer_size != size);
	end_page_addr =
		(void *)PAGE_ALIGN((uintptr_t)buffer->data + size);
	if (end_page_addr > has_page_addr)
		end_page_addr = has_page_addr;
	if (rayo_binder_update_page_range(proc, 1,
	    (void *)PAGE_ALIGN((uintptr_t)buffer->data), end_page_addr, NULL))
		return NULL;
	if (buffer_size != size) {
		struct rayo_binder_buffer *new_buffer;

		new_buffer = kzalloc(sizeof(*buffer), GFP_KERNEL);
		if (!new_buffer) {
			pr_err("%s: %d failed to alloc new buffer struct\n",
			       __func__, proc->pid);
			goto err_alloc_buf_struct_failed;
		}
		new_buffer->data = (u8 *)buffer->data + size;
		list_add(&new_buffer->entry, &buffer->entry);
		new_buffer->free = 1;
		rayo_binder_insert_free_buffer(proc, new_buffer);
	}

	rb_erase(best_fit, &proc->free_buffers);
	buffer->free = 0;
	rayo_binder_insert_allocated_buffer(proc, buffer);
	rayo_binder_debug(BINDER_DEBUG_BUFFER_ALLOC,
		     "%d: rayo_binder_alloc_buf size %zd got %pK\n",
		      proc->pid, size, buffer);
	buffer->data_size = data_size;
	buffer->offsets_size = offsets_size;
	buffer->extra_buffers_size = extra_buffers_size;
	buffer->async_transaction = is_async;
	if (is_async) {
		proc->free_async_space -= size + sizeof(struct rayo_binder_buffer);
		rayo_binder_debug(BINDER_DEBUG_BUFFER_ALLOC_ASYNC,
			     "%d: rayo_binder_alloc_buf size %zd async free %zd\n",
			      proc->pid, size, proc->free_async_space);
	}

	return buffer;

err_alloc_buf_struct_failed:
	rayo_binder_update_page_range(proc, 0,
				 (void *)PAGE_ALIGN((uintptr_t)buffer->data),
				 end_page_addr, NULL);
	return NULL;
}

static void *buffer_start_page(struct rayo_binder_buffer *buffer)
{
	return (void *)((uintptr_t)buffer->data & PAGE_MASK);
}

static void *prev_buffer_end_page(struct rayo_binder_buffer *buffer)
{
	return (void *)(((uintptr_t)(buffer->data) - 1) & PAGE_MASK);
}

static void rayo_binder_delete_free_buffer(struct rayo_binder_proc *proc,
				      struct rayo_binder_buffer *buffer)
{
	struct rayo_binder_buffer *prev, *next = NULL;
	bool to_free = true;
	BUG_ON(proc->buffers.next == &buffer->entry);
	prev = rayo_binder_buffer_prev(buffer);
	BUG_ON(!prev->free);
	if (prev_buffer_end_page(prev) == buffer_start_page(buffer)) {
		to_free = false;
		rayo_binder_debug(BINDER_DEBUG_BUFFER_ALLOC,
			     "%d: merge free, buffer %pK share page with %pK\n",
			      proc->pid, buffer->data, prev->data);
	}

	if (!list_is_last(&buffer->entry, &proc->buffers)) {
		next = rayo_binder_buffer_next(buffer);
		if (buffer_start_page(next) == buffer_start_page(buffer)) {
			to_free = false;
			rayo_binder_debug(BINDER_DEBUG_BUFFER_ALLOC,
				     "%d: merge free, buffer %pK share page with %pK\n",
				      proc->pid, buffer->data, prev->data);
		}
	}
	if (PAGE_ALIGNED(buffer->data)) {
		rayo_binder_debug(BINDER_DEBUG_BUFFER_ALLOC,
			     "%d: merge free, buffer start %pK is page aligned\n",
			     proc->pid, buffer->data);
		to_free = false;
	}

	if (to_free) {
		rayo_binder_debug(BINDER_DEBUG_BUFFER_ALLOC,
			     "%d: merge free, buffer %pK do not share page with %pK or %pK\n",
			     proc->pid, buffer->data,
			     prev->data, next->data);
		rayo_binder_update_page_range(proc, 0, buffer_start_page(buffer),
					 buffer_start_page(buffer) + PAGE_SIZE,
					 NULL);
	}
	list_del(&buffer->entry);
	kfree(buffer);
}

static void rayo_binder_free_buf(struct rayo_binder_proc *proc,
			    struct rayo_binder_buffer *buffer)
{
	size_t size, buffer_size;

	buffer_size = rayo_binder_buffer_size(proc, buffer);

	size = ALIGN(buffer->data_size, sizeof(void *)) +
		ALIGN(buffer->offsets_size, sizeof(void *)) +
		ALIGN(buffer->extra_buffers_size, sizeof(void *));

	rayo_binder_debug(BINDER_DEBUG_BUFFER_ALLOC,
		     "%d: rayo_binder_free_buf %pK size %zd buffer_size %zd\n",
		      proc->pid, buffer, size, buffer_size);

	BUG_ON(buffer->free);
	BUG_ON(size > buffer_size);
	BUG_ON(buffer->transaction != NULL);
	BUG_ON(buffer->data < proc->buffer);
	BUG_ON(buffer->data > proc->buffer + proc->buffer_size);

	if (buffer->async_transaction) {
		proc->free_async_space += size + sizeof(struct rayo_binder_buffer);

		rayo_binder_debug(BINDER_DEBUG_BUFFER_ALLOC_ASYNC,
			     "%d: rayo_binder_free_buf size %zd async free %zd\n",
			      proc->pid, size, proc->free_async_space);
	}

	rayo_binder_update_page_range(proc, 0,
		(void *)PAGE_ALIGN((uintptr_t)buffer->data),
		(void *)(((uintptr_t)buffer->data + buffer_size) & PAGE_MASK),
		NULL);
	rb_erase(&buffer->rb_node, &proc->allocated_buffers);
	buffer->free = 1;
	if (!list_is_last(&buffer->entry, &proc->buffers)) {
		struct rayo_binder_buffer *next = rayo_binder_buffer_next(buffer);

		if (next->free) {
			rb_erase(&next->rb_node, &proc->free_buffers);
			rayo_binder_delete_free_buffer(proc, next);
		}
	}
	if (proc->buffers.next != &buffer->entry) {
		struct rayo_binder_buffer *prev = rayo_binder_buffer_prev(buffer);

		if (prev->free) {
			rayo_binder_delete_free_buffer(proc, buffer);
			rb_erase(&prev->rb_node, &proc->free_buffers);
			buffer = prev;
		}
	}
	rayo_binder_insert_free_buffer(proc, buffer);
}

static struct rayo_binder_node *rayo_binder_get_node(struct rayo_binder_proc *proc,
					   rayo_binder_uintptr_t ptr)
{
	struct rb_node *n = proc->nodes.rb_node;
	struct rayo_binder_node *node;

	while (n) {
		node = rb_entry(n, struct rayo_binder_node, rb_node);

		if (ptr < node->ptr)
			n = n->rb_left;
		else if (ptr > node->ptr)
			n = n->rb_right;
		else
			return node;
	}
	return NULL;
}

static struct rayo_binder_node *rayo_binder_new_node(struct rayo_binder_proc *proc,
					   rayo_binder_uintptr_t ptr,
					   rayo_binder_uintptr_t cookie)
{
	struct rb_node **p = &proc->nodes.rb_node;
	struct rb_node *parent = NULL;
	struct rayo_binder_node *node;

	while (*p) {
		parent = *p;
		node = rb_entry(parent, struct rayo_binder_node, rb_node);

		if (ptr < node->ptr)
			p = &(*p)->rb_left;
		else if (ptr > node->ptr)
			p = &(*p)->rb_right;
		else
			return NULL;
	}

	node = kzalloc(sizeof(*node), GFP_KERNEL);
	if (node == NULL)
		return NULL;
	rayo_binder_stats_created(BINDER_STAT_NODE);
	rb_link_node(&node->rb_node, parent, p);
	rb_insert_color(&node->rb_node, &proc->nodes);
	node->debug_id = ++rayo_binder_last_id;
	node->proc = proc;
	node->ptr = ptr;
	node->cookie = cookie;
	node->work.type = BINDER_WORK_NODE;
	INIT_LIST_HEAD(&node->work.entry);
	INIT_LIST_HEAD(&node->async_todo);
	rayo_binder_debug(BINDER_DEBUG_INTERNAL_REFS,
		     "%d:%d node %d u%016llx c%016llx created\n",
		     proc->pid, current->pid, node->debug_id,
		     (u64)node->ptr, (u64)node->cookie);
	return node;
}

static int rayo_binder_inc_node(struct rayo_binder_node *node, int strong, int internal,
			   struct list_head *target_list)
{
	if (strong) {
		if (internal) {
			if (target_list == NULL &&
			    node->internal_strong_refs == 0 &&
			    !(node->proc &&
			      node == node->proc->context->
				      rayo_binder_context_mgr_node &&
			      node->has_strong_ref)) {
				rayo_binder_debug(BINDER_DEBUG_TOP_ERRORS,
					     "invalid inc strong node for %d\n",
					     node->debug_id);
				return -EINVAL;
			}
			node->internal_strong_refs++;
		} else
			node->local_strong_refs++;
		if (!node->has_strong_ref && target_list) {
			list_del_init(&node->work.entry);
			list_add_tail(&node->work.entry, target_list);
		}
	} else {
		if (!internal)
			node->local_weak_refs++;
		if (!node->has_weak_ref && list_empty(&node->work.entry)) {
			if (target_list == NULL) {
				rayo_binder_debug(BINDER_DEBUG_TOP_ERRORS,
					     "invalid inc weak node for %d\n",
					     node->debug_id);
				return -EINVAL;
			}
			list_add_tail(&node->work.entry, target_list);
		}
	}
	return 0;
}

static int rayo_binder_dec_node(struct rayo_binder_node *node, int strong, int internal)
{
	if (strong) {
		if (internal)
			node->internal_strong_refs--;
		else
			node->local_strong_refs--;
		if (node->local_strong_refs || node->internal_strong_refs)
			return 0;
	} else {
		if (!internal)
			node->local_weak_refs--;
		if (node->local_weak_refs || !hlist_empty(&node->refs))
			return 0;
	}
	if (node->proc && (node->has_strong_ref || node->has_weak_ref)) {
		if (list_empty(&node->work.entry)) {
			list_add_tail(&node->work.entry, &node->proc->todo);
			wake_up_interruptible(&node->proc->wait);
		}
	} else {
		if (hlist_empty(&node->refs) && !node->local_strong_refs &&
		    !node->local_weak_refs) {
			list_del_init(&node->work.entry);
			if (node->proc) {
				rb_erase(&node->rb_node, &node->proc->nodes);
				rayo_binder_debug(BINDER_DEBUG_INTERNAL_REFS,
					     "refless node %d deleted\n",
					     node->debug_id);
			} else {
				hlist_del(&node->dead_node);
				rayo_binder_debug(BINDER_DEBUG_INTERNAL_REFS,
					     "dead node %d deleted\n",
					     node->debug_id);
			}
			kfree(node);
			rayo_binder_stats_deleted(BINDER_STAT_NODE);
		}
	}

	return 0;
}


static struct rayo_binder_ref *rayo_binder_get_ref(struct rayo_binder_proc *proc,
					 uint32_t desc, bool need_strong_ref)
{
	struct rb_node *n = proc->refs_by_desc.rb_node;
	struct rayo_binder_ref *ref;

	while (n) {
		ref = rb_entry(n, struct rayo_binder_ref, rb_node_desc);

		if (desc < ref->desc) {
			n = n->rb_left;
		} else if (desc > ref->desc) {
			n = n->rb_right;
		} else if (need_strong_ref && !ref->strong) {
			rayo_binder_user_error("tried to use weak ref as strong ref\n");
			return NULL;
		} else {
			return ref;
		}
	}
	return NULL;
}

static struct rayo_binder_ref *rayo_binder_get_ref_for_node(struct rayo_binder_proc *proc,
						  struct rayo_binder_node *node)
{
	struct rb_node *n;
	struct rb_node **p = &proc->refs_by_node.rb_node;
	struct rb_node *parent = NULL;
	struct rayo_binder_ref *ref, *new_ref;
	struct rayo_binder_context *context = proc->context;

	while (*p) {
		parent = *p;
		ref = rb_entry(parent, struct rayo_binder_ref, rb_node_node);

		if (node < ref->node)
			p = &(*p)->rb_left;
		else if (node > ref->node)
			p = &(*p)->rb_right;
		else
			return ref;
	}
	new_ref = kzalloc(sizeof(*ref), GFP_KERNEL);
	if (new_ref == NULL)
		return NULL;
	rayo_binder_stats_created(BINDER_STAT_REF);
	new_ref->debug_id = ++rayo_binder_last_id;
	new_ref->proc = proc;
	new_ref->node = node;
	rb_link_node(&new_ref->rb_node_node, parent, p);
	rb_insert_color(&new_ref->rb_node_node, &proc->refs_by_node);

	new_ref->desc = (node == context->rayo_binder_context_mgr_node) ? 0 : 1;
	for (n = rb_first(&proc->refs_by_desc); n != NULL; n = rb_next(n)) {
		ref = rb_entry(n, struct rayo_binder_ref, rb_node_desc);
		if (ref->desc > new_ref->desc)
			break;
		new_ref->desc = ref->desc + 1;
	}

	p = &proc->refs_by_desc.rb_node;
	while (*p) {
		parent = *p;
		ref = rb_entry(parent, struct rayo_binder_ref, rb_node_desc);

		if (new_ref->desc < ref->desc)
			p = &(*p)->rb_left;
		else if (new_ref->desc > ref->desc)
			p = &(*p)->rb_right;
		else
			BUG();
	}
	rb_link_node(&new_ref->rb_node_desc, parent, p);
	rb_insert_color(&new_ref->rb_node_desc, &proc->refs_by_desc);
	if (node) {
		hlist_add_head(&new_ref->node_entry, &node->refs);

		rayo_binder_debug(BINDER_DEBUG_INTERNAL_REFS,
			     "%d new ref %d desc %d for node %d\n",
			      proc->pid, new_ref->debug_id, new_ref->desc,
			      node->debug_id);
	} else {
		rayo_binder_debug(BINDER_DEBUG_INTERNAL_REFS,
			     "%d new ref %d desc %d for dead node\n",
			      proc->pid, new_ref->debug_id, new_ref->desc);
	}
	return new_ref;
}

static void rayo_binder_delete_ref(struct rayo_binder_ref *ref)
{
	rayo_binder_debug(BINDER_DEBUG_INTERNAL_REFS,
		     "%d delete ref %d desc %d for node %d\n",
		      ref->proc->pid, ref->debug_id, ref->desc,
		      ref->node->debug_id);

	rb_erase(&ref->rb_node_desc, &ref->proc->refs_by_desc);
	rb_erase(&ref->rb_node_node, &ref->proc->refs_by_node);
	if (ref->strong)
		rayo_binder_dec_node(ref->node, 1, 1);
	hlist_del(&ref->node_entry);
	rayo_binder_dec_node(ref->node, 0, 1);
	if (ref->death) {
		rayo_binder_debug(BINDER_DEBUG_DEAD_BINDER,
			     "%d delete ref %d desc %d has death notification\n",
			      ref->proc->pid, ref->debug_id, ref->desc);
		list_del(&ref->death->work.entry);
		kfree(ref->death);
		rayo_binder_stats_deleted(BINDER_STAT_DEATH);
	}
	kfree(ref);
	rayo_binder_stats_deleted(BINDER_STAT_REF);
}

static int rayo_binder_inc_ref(struct rayo_binder_ref *ref, int strong,
			  struct list_head *target_list)
{
	int ret;

	if (strong) {
		if (ref->strong == 0) {
			ret = rayo_binder_inc_node(ref->node, 1, 1, target_list);
			if (ret)
				return ret;
		}
		ref->strong++;
	} else {
		if (ref->weak == 0) {
			ret = rayo_binder_inc_node(ref->node, 0, 1, target_list);
			if (ret)
				return ret;
		}
		ref->weak++;
	}
	return 0;
}


static int rayo_binder_dec_ref(struct rayo_binder_ref *ref, int strong)
{
	if (strong) {
		if (ref->strong == 0) {
			rayo_binder_user_error("%d invalid dec strong, ref %d desc %d s %d w %d\n",
					  ref->proc->pid, ref->debug_id,
					  ref->desc, ref->strong, ref->weak);
			return -EINVAL;
		}
		ref->strong--;
		if (ref->strong == 0) {
			int ret;

			ret = rayo_binder_dec_node(ref->node, strong, 1);
			if (ret)
				return ret;
		}
	} else {
		if (ref->weak == 0) {
			rayo_binder_user_error("%d invalid dec weak, ref %d desc %d s %d w %d\n",
					  ref->proc->pid, ref->debug_id,
					  ref->desc, ref->strong, ref->weak);
			return -EINVAL;
		}
		ref->weak--;
	}
	if (ref->strong == 0 && ref->weak == 0)
		rayo_binder_delete_ref(ref);
	return 0;
}

static void rayo_binder_pop_transaction(struct rayo_binder_thread *target_thread,
				   struct rayo_binder_transaction *t)
{
	if (target_thread) {
		BUG_ON(target_thread->transaction_stack != t);
		BUG_ON(target_thread->transaction_stack->from != target_thread);
		target_thread->transaction_stack =
			target_thread->transaction_stack->from_parent;
		t->from = NULL;
	}
	t->need_reply = 0;
	if (t->buffer)
		t->buffer->transaction = NULL;
	kfree(t);
	rayo_binder_stats_deleted(BINDER_STAT_TRANSACTION);
}

static void rayo_binder_send_failed_reply(struct rayo_binder_transaction *t,
				     uint32_t error_code)
{
	struct rayo_binder_thread *target_thread;

	BUG_ON(t->flags & TF_ONE_WAY);
	while (1) {
		target_thread = t->from;
		if (target_thread) {
			if (target_thread->return_error != BR_OK &&
			   target_thread->return_error2 == BR_OK) {
				target_thread->return_error2 =
					target_thread->return_error;
				target_thread->return_error = BR_OK;
			}
			if (target_thread->return_error == BR_OK) {
				rayo_binder_debug(BINDER_DEBUG_FAILED_TRANSACTION,
					     "send failed reply for transaction %d to %d:%d\n",
					      t->debug_id, target_thread->proc->pid,
					      target_thread->pid);

				rayo_binder_pop_transaction(target_thread, t);
				target_thread->return_error = error_code;
				wake_up_interruptible(&target_thread->wait);
			} else {
				rayo_binder_debug(BINDER_DEBUG_TOP_ERRORS,
					     "reply failed, target thread, %d:%d, has error code %d already\n",
					     target_thread->proc->pid,
					     target_thread->pid,
					     target_thread->return_error);
			}
			return;
		} else {
			struct rayo_binder_transaction *next = t->from_parent;

			rayo_binder_debug(BINDER_DEBUG_FAILED_TRANSACTION,
				     "send failed reply for transaction %d, target dead\n",
				     t->debug_id);

			rayo_binder_pop_transaction(target_thread, t);
			if (next == NULL) {
				rayo_binder_debug(BINDER_DEBUG_DEAD_BINDER,
					     "reply failed, no target thread at root\n");
				return;
			}
			t = next;
			rayo_binder_debug(BINDER_DEBUG_DEAD_BINDER,
				     "reply failed, no target thread -- retry %d\n",
				      t->debug_id);
		}
	}
}

/**
 * rayo_binder_validate_object() - checks for a valid metadata object in a buffer.
 * @buffer:	rayo_binder_buffer that we're parsing.
 * @offset:	offset in the buffer at which to validate an object.
 *
 * Return:	If there's a valid metadata object at @offset in @buffer, the
 *		size of that object. Otherwise, it returns zero.
 */
static size_t rayo_binder_validate_object(struct rayo_binder_buffer *buffer, u64 offset)
{
	/* Check if we can read a header first */
	struct rayo_binder_object_header *hdr;
	size_t object_size = 0;

	if (offset > buffer->data_size - sizeof(*hdr) ||
	    buffer->data_size < sizeof(*hdr) ||
	    !IS_ALIGNED(offset, sizeof(u32)))
		return 0;

	/* Ok, now see if we can read a complete object. */
	hdr = (struct rayo_binder_object_header *)(buffer->data + offset);
	switch (hdr->type) {
	case BINDER_TYPE_BINDER:
	case BINDER_TYPE_WEAK_BINDER:
	case BINDER_TYPE_HANDLE:
	case BINDER_TYPE_WEAK_HANDLE:
		object_size = sizeof(struct flat_rayo_binder_object);
		break;
	case BINDER_TYPE_FD:
		object_size = sizeof(struct rayo_binder_fd_object);
		break;
	case BINDER_TYPE_PTR:
		object_size = sizeof(struct rayo_binder_buffer_object);
		break;
	case BINDER_TYPE_FDA:
		object_size = sizeof(struct rayo_binder_fd_array_object);
		break;
	default:
		return 0;
	}
	if (offset <= buffer->data_size - object_size &&
	    buffer->data_size >= object_size)
		return object_size;
	else
		return 0;
}

/**
 * rayo_binder_validate_ptr() - validates rayo_binder_buffer_object in a rayo_binder_buffer.
 * @b:		rayo_binder_buffer containing the object
 * @index:	index in offset array at which the rayo_binder_buffer_object is
 *		located
 * @start:	points to the start of the offset array
 * @num_valid:	the number of valid offsets in the offset array
 *
 * Return:	If @index is within the valid range of the offset array
 *		described by @start and @num_valid, and if there's a valid
 *		rayo_binder_buffer_object at the offset found in index @index
 *		of the offset array, that object is returned. Otherwise,
 *		%NULL is returned.
 *		Note that the offset found in index @index itself is not
 *		verified; this function assumes that @num_valid elements
 *		from @start were previously verified to have valid offsets.
 */
static struct rayo_binder_buffer_object *rayo_binder_validate_ptr(struct rayo_binder_buffer *b,
							rayo_binder_size_t index,
							rayo_binder_size_t *start,
							rayo_binder_size_t num_valid)
{
	struct rayo_binder_buffer_object *buffer_obj;
	rayo_binder_size_t *offp;

	if (index >= num_valid)
		return NULL;

	offp = start + index;
	buffer_obj = (struct rayo_binder_buffer_object *)(b->data + *offp);
	if (buffer_obj->hdr.type != BINDER_TYPE_PTR)
		return NULL;

	return buffer_obj;
}

/**
 * rayo_binder_validate_fixup() - validates pointer/fd fixups happen in order.
 * @b:			transaction buffer
 * @objects_start	start of objects buffer
 * @buffer:		rayo_binder_buffer_object in which to fix up
 * @offset:		start offset in @buffer to fix up
 * @last_obj:		last rayo_binder_buffer_object that we fixed up in
 * @last_min_offset:	minimum fixup offset in @last_obj
 *
 * Return:		%true if a fixup in buffer @buffer at offset @offset is
 *			allowed.
 *
 * For safety reasons, we only allow fixups inside a buffer to happen
 * at increasing offsets; additionally, we only allow fixup on the last
 * buffer object that was verified, or one of its parents.
 *
 * Example of what is allowed:
 *
 * A
 *   B (parent = A, offset = 0)
 *   C (parent = A, offset = 16)
 *     D (parent = C, offset = 0)
 *   E (parent = A, offset = 32) // min_offset is 16 (C.parent_offset)
 *
 * Examples of what is not allowed:
 *
 * Decreasing offsets within the same parent:
 * A
 *   C (parent = A, offset = 16)
 *   B (parent = A, offset = 0) // decreasing offset within A
 *
 * Referring to a parent that wasn't the last object or any of its parents:
 * A
 *   B (parent = A, offset = 0)
 *   C (parent = A, offset = 0)
 *   C (parent = A, offset = 16)
 *     D (parent = B, offset = 0) // B is not A or any of A's parents
 */
static bool rayo_binder_validate_fixup(struct rayo_binder_buffer *b,
				  rayo_binder_size_t *objects_start,
				  struct rayo_binder_buffer_object *buffer,
				  rayo_binder_size_t fixup_offset,
				  struct rayo_binder_buffer_object *last_obj,
				  rayo_binder_size_t last_min_offset)
{
	if (!last_obj) {
		/* Nothing to fix up in */
		return false;
	}

	while (last_obj != buffer) {
		/*
		 * Safe to retrieve the parent of last_obj, since it
		 * was already previously verified by the driver.
		 */
		if ((last_obj->flags & BINDER_BUFFER_FLAG_HAS_PARENT) == 0)
			return false;
		last_min_offset = last_obj->parent_offset + sizeof(uintptr_t);
		last_obj = (struct rayo_binder_buffer_object *)
			(b->data + *(objects_start + last_obj->parent));
	}
	return (fixup_offset >= last_min_offset);
}

static void rayo_binder_transaction_buffer_release(struct rayo_binder_proc *proc,
					      struct rayo_binder_buffer *buffer,
					      rayo_binder_size_t *failed_at)
{
	rayo_binder_size_t *offp, *off_start, *off_end;
	int debug_id = buffer->debug_id;

	rayo_binder_debug(BINDER_DEBUG_TRANSACTION,
		     "%d buffer release %d, size %zd-%zd, failed at %pK\n",
		     proc->pid, buffer->debug_id,
		     buffer->data_size, buffer->offsets_size, failed_at);

	if (buffer->target_node)
		rayo_binder_dec_node(buffer->target_node, 1, 0);

	off_start = (rayo_binder_size_t *)(buffer->data +
				      ALIGN(buffer->data_size, sizeof(void *)));
	if (failed_at)
		off_end = failed_at;
	else
		off_end = (void *)off_start + buffer->offsets_size;
	for (offp = off_start; offp < off_end; offp++) {
		struct rayo_binder_object_header *hdr;
		size_t object_size = rayo_binder_validate_object(buffer, *offp);

		if (object_size == 0) {
			rayo_binder_debug(BINDER_DEBUG_TOP_ERRORS,
				     "transaction release %d bad object at offset %lld, size %zd\n",
				     debug_id, (u64)*offp, buffer->data_size);
			continue;
		}
		hdr = (struct rayo_binder_object_header *)(buffer->data + *offp);
		switch (hdr->type) {
		case BINDER_TYPE_BINDER:
		case BINDER_TYPE_WEAK_BINDER: {
			struct flat_rayo_binder_object *fp;
			struct rayo_binder_node *node;

			fp = to_flat_rayo_binder_object(hdr);
			node = rayo_binder_get_node(proc, fp->binder);
			if (node == NULL) {
				rayo_binder_debug(BINDER_DEBUG_TOP_ERRORS,
					     "transaction release %d bad node %016llx\n",
					     debug_id, (u64)fp->binder);
				break;
			}
			rayo_binder_debug(BINDER_DEBUG_TRANSACTION,
				     "        node %d u%016llx\n",
				     node->debug_id, (u64)node->ptr);
			rayo_binder_dec_node(node, hdr->type == BINDER_TYPE_BINDER,
					0);
		} break;
		case BINDER_TYPE_HANDLE:
		case BINDER_TYPE_WEAK_HANDLE: {
			struct flat_rayo_binder_object *fp;
			struct rayo_binder_ref *ref;

			fp = to_flat_rayo_binder_object(hdr);
			ref = rayo_binder_get_ref(proc, fp->handle,
					     hdr->type == BINDER_TYPE_HANDLE);
			if (ref == NULL) {
				rayo_binder_debug(BINDER_DEBUG_TOP_ERRORS,
					     "transaction release %d bad handle %d\n",
					     debug_id, fp->handle);
				break;
			}
			rayo_binder_debug(BINDER_DEBUG_TRANSACTION,
				     "        ref %d desc %d (node %d)\n",
				     ref->debug_id, ref->desc, ref->node->debug_id);
			rayo_binder_dec_ref(ref, hdr->type == BINDER_TYPE_HANDLE);
		} break;

		case BINDER_TYPE_FD: {
			struct rayo_binder_fd_object *fp = to_rayo_binder_fd_object(hdr);

			rayo_binder_debug(BINDER_DEBUG_TRANSACTION,
				     "        fd %d\n", fp->fd);
			if (failed_at)
				task_close_fd(proc, fp->fd);
		} break;
		case BINDER_TYPE_PTR:
			/*
			 * Nothing to do here, this will get cleaned up when the
			 * transaction buffer gets freed
			 */
			break;
		case BINDER_TYPE_FDA: {
			struct rayo_binder_fd_array_object *fda;
			struct rayo_binder_buffer_object *parent;
			uintptr_t parent_buffer;
			u32 *fd_array;
			size_t fd_index;
			rayo_binder_size_t fd_buf_size;

			fda = to_rayo_binder_fd_array_object(hdr);
			parent = rayo_binder_validate_ptr(buffer, fda->parent,
						     off_start,
						     offp - off_start);
			if (!parent) {
				pr_err("transaction release %d bad parent offset",
				       debug_id);
				continue;
			}
			/*
			 * Since the parent was already fixed up, convert it
			 * back to kernel address space to access it
			 */
			parent_buffer = parent->buffer -
				proc->user_buffer_offset;

			fd_buf_size = sizeof(u32) * fda->num_fds;
			if (fda->num_fds >= SIZE_MAX / sizeof(u32)) {
				pr_err("transaction release %d invalid number of fds (%lld)\n",
				       debug_id, (u64)fda->num_fds);
				continue;
			}
			if (fd_buf_size > parent->length ||
			    fda->parent_offset > parent->length - fd_buf_size) {
				/* No space for all file descriptors here. */
				pr_err("transaction release %d not enough space for %lld fds in buffer\n",
				       debug_id, (u64)fda->num_fds);
				continue;
			}
			fd_array = (u32 *)(uintptr_t)
				(parent_buffer + fda->parent_offset);
			for (fd_index = 0; fd_index < fda->num_fds; fd_index++)
				task_close_fd(proc, fd_array[fd_index]);
		} break;
		default:
			rayo_binder_debug(BINDER_DEBUG_TOP_ERRORS,
				     "transaction release %d bad object type %x\n",
				     debug_id, hdr->type);
			break;
		}
	}
}

static int rayo_binder_translate_binder(struct flat_rayo_binder_object *fp,
				   struct rayo_binder_transaction *t,
				   struct rayo_binder_thread *thread)
{
	struct rayo_binder_node *node;
	struct rayo_binder_ref *ref;
	struct rayo_binder_proc *proc = thread->proc;
	struct rayo_binder_proc *target_proc = t->to_proc;

	node = rayo_binder_get_node(proc, fp->binder);
	if (!node) {
		node = rayo_binder_new_node(proc, fp->binder, fp->cookie);
		if (!node)
			return -ENOMEM;

		node->min_priority = fp->flags & FLAT_BINDER_FLAG_PRIORITY_MASK;
		node->accept_fds = !!(fp->flags & FLAT_BINDER_FLAG_ACCEPTS_FDS);
	}
	if (fp->cookie != node->cookie) {
		rayo_binder_user_error("%d:%d sending u%016llx node %d, cookie mismatch %016llx != %016llx\n",
				  proc->pid, thread->pid, (u64)fp->binder,
				  node->debug_id, (u64)fp->cookie,
				  (u64)node->cookie);
		return -EINVAL;
	}
	if (security_binder_transfer_binder(proc->tsk, target_proc->tsk))
		return -EPERM;

	ref = rayo_binder_get_ref_for_node(target_proc, node);
	if (!ref)
		return -EINVAL;

	if (fp->hdr.type == BINDER_TYPE_BINDER)
		fp->hdr.type = BINDER_TYPE_HANDLE;
	else
		fp->hdr.type = BINDER_TYPE_WEAK_HANDLE;
	fp->binder = 0;
	fp->handle = ref->desc;
	fp->cookie = 0;
	rayo_binder_inc_ref(ref, fp->hdr.type == BINDER_TYPE_HANDLE, &thread->todo);

	trace_rayo_binder_transaction_node_to_ref(t, node, ref);
	rayo_binder_debug(BINDER_DEBUG_TRANSACTION,
		     "        node %d u%016llx -> ref %d desc %d\n",
		     node->debug_id, (u64)node->ptr,
		     ref->debug_id, ref->desc);

	return 0;
}

static int rayo_binder_translate_handle(struct flat_rayo_binder_object *fp,
				   struct rayo_binder_transaction *t,
				   struct rayo_binder_thread *thread)
{
	struct rayo_binder_ref *ref;
	struct rayo_binder_proc *proc = thread->proc;
	struct rayo_binder_proc *target_proc = t->to_proc;

	ref = rayo_binder_get_ref(proc, fp->handle,
			     fp->hdr.type == BINDER_TYPE_HANDLE);
	if (!ref) {
		rayo_binder_user_error("%d:%d got transaction with invalid handle, %d\n",
				  proc->pid, thread->pid, fp->handle);
		return -EINVAL;
	}
	if (security_binder_transfer_binder(proc->tsk, target_proc->tsk))
		return -EPERM;

	if (ref->node->proc == target_proc) {
		if (fp->hdr.type == BINDER_TYPE_HANDLE)
			fp->hdr.type = BINDER_TYPE_BINDER;
		else
			fp->hdr.type = BINDER_TYPE_WEAK_BINDER;
		fp->binder = ref->node->ptr;
		fp->cookie = ref->node->cookie;
		rayo_binder_inc_node(ref->node, fp->hdr.type == BINDER_TYPE_BINDER,
				0, NULL);
		trace_rayo_binder_transaction_ref_to_node(t, ref);
		rayo_binder_debug(BINDER_DEBUG_TRANSACTION,
			     "        ref %d desc %d -> node %d u%016llx\n",
			     ref->debug_id, ref->desc, ref->node->debug_id,
			     (u64)ref->node->ptr);
	} else {
		struct rayo_binder_ref *new_ref;

		new_ref = rayo_binder_get_ref_for_node(target_proc, ref->node);
		if (!new_ref)
			return -EINVAL;

		fp->binder = 0;
		fp->handle = new_ref->desc;
		fp->cookie = 0;
		rayo_binder_inc_ref(new_ref, fp->hdr.type == BINDER_TYPE_HANDLE,
			       NULL);
		trace_rayo_binder_transaction_ref_to_ref(t, ref, new_ref);
		rayo_binder_debug(BINDER_DEBUG_TRANSACTION,
			     "        ref %d desc %d -> ref %d desc %d (node %d)\n",
			     ref->debug_id, ref->desc, new_ref->debug_id,
			     new_ref->desc, ref->node->debug_id);
	}
	return 0;
}

static int rayo_binder_translate_fd(int fd,
			       struct rayo_binder_transaction *t,
			       struct rayo_binder_thread *thread,
			       struct rayo_binder_transaction *in_reply_to)
{
	struct rayo_binder_proc *proc = thread->proc;
	struct rayo_binder_proc *target_proc = t->to_proc;
	int target_fd;
	struct file *file;
	int ret;
	bool target_allows_fd;

	if (in_reply_to)
		target_allows_fd = !!(in_reply_to->flags & TF_ACCEPT_FDS);
	else
		target_allows_fd = t->buffer->target_node->accept_fds;
	if (!target_allows_fd) {
		rayo_binder_user_error("%d:%d got %s with fd, %d, but target does not allow fds\n",
				  proc->pid, thread->pid,
				  in_reply_to ? "reply" : "transaction",
				  fd);
		ret = -EPERM;
		goto err_fd_not_accepted;
	}

	file = fget(fd);
	if (!file) {
		rayo_binder_user_error("%d:%d got transaction with invalid fd, %d\n",
				  proc->pid, thread->pid, fd);
		ret = -EBADF;
		goto err_fget;
	}
	ret = security_binder_transfer_file(proc->tsk, target_proc->tsk, file);
	if (ret < 0) {
		ret = -EPERM;
		goto err_security;
	}

	target_fd = task_get_unused_fd_flags(target_proc, O_CLOEXEC);
	if (target_fd < 0) {
		ret = -ENOMEM;
		goto err_get_unused_fd;
	}
	task_fd_install(target_proc, target_fd, file);
	trace_rayo_binder_transaction_fd(t, fd, target_fd);
	rayo_binder_debug(BINDER_DEBUG_TRANSACTION, "        fd %d -> %d\n",
		     fd, target_fd);

	return target_fd;

err_get_unused_fd:
err_security:
	fput(file);
err_fget:
err_fd_not_accepted:
	return ret;
}

static int rayo_binder_translate_fd_array(struct rayo_binder_fd_array_object *fda,
				     struct rayo_binder_buffer_object *parent,
				     struct rayo_binder_transaction *t,
				     struct rayo_binder_thread *thread,
				     struct rayo_binder_transaction *in_reply_to)
{
	rayo_binder_size_t fdi, fd_buf_size, num_installed_fds;
	int target_fd;
	uintptr_t parent_buffer;
	u32 *fd_array;
	struct rayo_binder_proc *proc = thread->proc;
	struct rayo_binder_proc *target_proc = t->to_proc;

	fd_buf_size = sizeof(u32) * fda->num_fds;
	if (fda->num_fds >= SIZE_MAX / sizeof(u32)) {
		rayo_binder_user_error("%d:%d got transaction with invalid number of fds (%lld)\n",
				  proc->pid, thread->pid, (u64)fda->num_fds);
		return -EINVAL;
	}
	if (fd_buf_size > parent->length ||
	    fda->parent_offset > parent->length - fd_buf_size) {
		/* No space for all file descriptors here. */
		rayo_binder_user_error("%d:%d not enough space to store %lld fds in buffer\n",
				  proc->pid, thread->pid, (u64)fda->num_fds);
		return -EINVAL;
	}
	/*
	 * Since the parent was already fixed up, convert it
	 * back to the kernel address space to access it
	 */
	parent_buffer = parent->buffer - target_proc->user_buffer_offset;
	fd_array = (u32 *)(uintptr_t)(parent_buffer + fda->parent_offset);
	if (!IS_ALIGNED((unsigned long)fd_array, sizeof(u32))) {
		rayo_binder_user_error("%d:%d parent offset not aligned correctly.\n",
				  proc->pid, thread->pid);
		return -EINVAL;
	}
	for (fdi = 0; fdi < fda->num_fds; fdi++) {
		target_fd = rayo_binder_translate_fd(fd_array[fdi], t, thread,
						in_reply_to);
		if (target_fd < 0)
			goto err_translate_fd_failed;
		fd_array[fdi] = target_fd;
	}
	return 0;

err_translate_fd_failed:
	/*
	 * Failed to allocate fd or security error, free fds
	 * installed so far.
	 */
	num_installed_fds = fdi;
	for (fdi = 0; fdi < num_installed_fds; fdi++)
		task_close_fd(target_proc, fd_array[fdi]);
	return target_fd;
}

static int rayo_binder_fixup_parent(struct rayo_binder_transaction *t,
			       struct rayo_binder_thread *thread,
			       struct rayo_binder_buffer_object *bp,
			       rayo_binder_size_t *off_start,
			       rayo_binder_size_t num_valid,
			       struct rayo_binder_buffer_object *last_fixup_obj,
			       rayo_binder_size_t last_fixup_min_off)
{
	struct rayo_binder_buffer_object *parent;
	u8 *parent_buffer;
	struct rayo_binder_buffer *b = t->buffer;
	struct rayo_binder_proc *proc = thread->proc;
	struct rayo_binder_proc *target_proc = t->to_proc;

	if (!(bp->flags & BINDER_BUFFER_FLAG_HAS_PARENT))
		return 0;

	parent = rayo_binder_validate_ptr(b, bp->parent, off_start, num_valid);
	if (!parent) {
		rayo_binder_user_error("%d:%d got transaction with invalid parent offset or type\n",
				  proc->pid, thread->pid);
		return -EINVAL;
	}

	if (!rayo_binder_validate_fixup(b, off_start,
				   parent, bp->parent_offset,
				   last_fixup_obj,
				   last_fixup_min_off)) {
		rayo_binder_user_error("%d:%d got transaction with out-of-order buffer fixup\n",
				  proc->pid, thread->pid);
		return -EINVAL;
	}

	if (parent->length < sizeof(rayo_binder_uintptr_t) ||
	    bp->parent_offset > parent->length - sizeof(rayo_binder_uintptr_t)) {
		/* No space for a pointer here! */
		rayo_binder_user_error("%d:%d got transaction with invalid parent offset\n",
				  proc->pid, thread->pid);
		return -EINVAL;
	}
	parent_buffer = (u8 *)(uintptr_t)(parent->buffer -
			       target_proc->user_buffer_offset);
	*(rayo_binder_uintptr_t *)(parent_buffer + bp->parent_offset) = bp->buffer;

	return 0;
}

static void rayo_binder_transaction(struct rayo_binder_proc *proc,
			       struct rayo_binder_thread *thread,
			       struct rayo_binder_transaction_data *tr, int reply,
			       rayo_binder_size_t extra_buffers_size)
{
	int ret;
	struct rayo_binder_transaction *t;
	struct rayo_binder_work *tcomplete;
	rayo_binder_size_t *offp, *off_end, *off_start;
	rayo_binder_size_t off_min;
	u8 *sg_bufp, *sg_buf_end;
	struct rayo_binder_proc *target_proc;
	struct rayo_binder_thread *target_thread = NULL;
	struct rayo_binder_node *target_node = NULL;
	struct list_head *target_list;
	wait_queue_head_t *target_wait;
	struct rayo_binder_transaction *in_reply_to = NULL;
	struct rayo_binder_transaction_log_entry *e;
	uint32_t return_error;
	struct rayo_binder_buffer_object *last_fixup_obj = NULL;
	rayo_binder_size_t last_fixup_min_off = 0;
	struct rayo_binder_context *context = proc->context;

	e = rayo_binder_transaction_log_add(&rayo_binder_transaction_log);
	e->call_type = reply ? 2 : !!(tr->flags & TF_ONE_WAY);
	e->from_proc = proc->pid;
	e->from_thread = thread->pid;
	e->target_handle = tr->target.handle;
	e->data_size = tr->data_size;
	e->offsets_size = tr->offsets_size;
	e->context_name = proc->context->name;

	if (reply) {
		in_reply_to = thread->transaction_stack;
		if (in_reply_to == NULL) {
			rayo_binder_user_error("%d:%d got reply transaction with no transaction stack\n",
					  proc->pid, thread->pid);
			return_error = BR_FAILED_REPLY;
			goto err_empty_call_stack;
		}
		rayo_binder_set_nice(in_reply_to->saved_priority);
		if (in_reply_to->to_thread != thread) {
			rayo_binder_user_error("%d:%d got reply transaction with bad transaction stack, transaction %d has target %d:%d\n",
				proc->pid, thread->pid, in_reply_to->debug_id,
				in_reply_to->to_proc ?
				in_reply_to->to_proc->pid : 0,
				in_reply_to->to_thread ?
				in_reply_to->to_thread->pid : 0);
			return_error = BR_FAILED_REPLY;
			in_reply_to = NULL;
			goto err_bad_call_stack;
		}
		thread->transaction_stack = in_reply_to->to_parent;
		target_thread = in_reply_to->from;
		if (target_thread == NULL) {
			return_error = BR_DEAD_REPLY;
			goto err_dead_binder;
		}
		if (target_thread->transaction_stack != in_reply_to) {
			rayo_binder_user_error("%d:%d got reply transaction with bad target transaction stack %d, expected %d\n",
				proc->pid, thread->pid,
				target_thread->transaction_stack ?
				target_thread->transaction_stack->debug_id : 0,
				in_reply_to->debug_id);
			return_error = BR_FAILED_REPLY;
			in_reply_to = NULL;
			target_thread = NULL;
			goto err_dead_binder;
		}
		target_proc = target_thread->proc;
	} else {
		if (tr->target.handle) {
			struct rayo_binder_ref *ref;

			ref = rayo_binder_get_ref(proc, tr->target.handle, true);
			if (ref == NULL) {
				rayo_binder_user_error("%d:%d got transaction to invalid handle\n",
					proc->pid, thread->pid);
				return_error = BR_FAILED_REPLY;
				goto err_invalid_target_handle;
			}
			target_node = ref->node;
		} else {
			target_node = context->rayo_binder_context_mgr_node;
			if (target_node == NULL) {
				return_error = BR_DEAD_REPLY;
				goto err_no_context_mgr_node;
			}
		}
		e->to_node = target_node->debug_id;
		target_proc = target_node->proc;
		if (target_proc == NULL) {
			return_error = BR_DEAD_REPLY;
			goto err_dead_binder;
		}
		if (security_binder_transaction(proc->tsk, target_proc->tsk) < 0) {
			return_error = BR_FAILED_REPLY;
			goto err_invalid_target_handle;
		}
		if (!(tr->flags & TF_ONE_WAY) && thread->transaction_stack) {
			struct rayo_binder_transaction *tmp;

			tmp = thread->transaction_stack;
			if (tmp->to_thread != thread) {
				rayo_binder_user_error("%d:%d got new transaction with bad transaction stack, transaction %d has target %d:%d\n",
					proc->pid, thread->pid, tmp->debug_id,
					tmp->to_proc ? tmp->to_proc->pid : 0,
					tmp->to_thread ?
					tmp->to_thread->pid : 0);
				return_error = BR_FAILED_REPLY;
				goto err_bad_call_stack;
			}
			while (tmp) {
				if (tmp->from && tmp->from->proc == target_proc)
					target_thread = tmp->from;
				tmp = tmp->from_parent;
			}
		}
	}
	if (target_thread) {
		e->to_thread = target_thread->pid;
		target_list = &target_thread->todo;
		target_wait = &target_thread->wait;
	} else {
		target_list = &target_proc->todo;
		target_wait = &target_proc->wait;
	}
	e->to_proc = target_proc->pid;

	/* TODO: reuse incoming transaction for reply */
	t = kzalloc(sizeof(*t), GFP_KERNEL);
	if (t == NULL) {
		return_error = BR_FAILED_REPLY;
		goto err_alloc_t_failed;
	}
	rayo_binder_stats_created(BINDER_STAT_TRANSACTION);

	tcomplete = kzalloc(sizeof(*tcomplete), GFP_KERNEL);
	if (tcomplete == NULL) {
		return_error = BR_FAILED_REPLY;
		goto err_alloc_tcomplete_failed;
	}
	rayo_binder_stats_created(BINDER_STAT_TRANSACTION_COMPLETE);

	t->debug_id = ++rayo_binder_last_id;
	e->debug_id = t->debug_id;

	if (reply)
		rayo_binder_debug(BINDER_DEBUG_TRANSACTION,
			     "%d:%d BC_REPLY %d -> %d:%d, data %016llx-%016llx size %lld-%lld-%lld\n",
			     proc->pid, thread->pid, t->debug_id,
			     target_proc->pid, target_thread->pid,
			     (u64)tr->data.ptr.buffer,
			     (u64)tr->data.ptr.offsets,
			     (u64)tr->data_size, (u64)tr->offsets_size,
			     (u64)extra_buffers_size);
	else
		rayo_binder_debug(BINDER_DEBUG_TRANSACTION,
			     "%d:%d BC_TRANSACTION %d -> %d - node %d, data %016llx-%016llx size %lld-%lld-%lld\n",
			     proc->pid, thread->pid, t->debug_id,
			     target_proc->pid, target_node->debug_id,
			     (u64)tr->data.ptr.buffer,
			     (u64)tr->data.ptr.offsets,
			     (u64)tr->data_size, (u64)tr->offsets_size,
			     (u64)extra_buffers_size);

	if (!reply && !(tr->flags & TF_ONE_WAY))
		t->from = thread;
	else
		t->from = NULL;
	t->sender_euid = proc->tsk->cred->euid;
	t->to_proc = target_proc;
	t->to_thread = target_thread;
	t->code = tr->code;
	t->flags = tr->flags;
	t->priority = task_nice(current);

	trace_rayo_binder_transaction(reply, t, target_node);

	t->buffer = rayo_binder_alloc_buf(target_proc, tr->data_size,
		tr->offsets_size, extra_buffers_size,
		!reply && (t->flags & TF_ONE_WAY));
	if (t->buffer == NULL) {
		return_error = BR_FAILED_REPLY;
		goto err_rayo_binder_alloc_buf_failed;
	}
	t->buffer->allow_user_free = 0;
	t->buffer->debug_id = t->debug_id;
	t->buffer->transaction = t;
	t->buffer->target_node = target_node;
	trace_rayo_binder_transaction_alloc_buf(t->buffer);
	if (target_node)
		rayo_binder_inc_node(target_node, 1, 0, NULL);

	off_start = (rayo_binder_size_t *)(t->buffer->data +
				      ALIGN(tr->data_size, sizeof(void *)));
	offp = off_start;

	if (copy_from_user(t->buffer->data, (const void __user *)(uintptr_t)
			   tr->data.ptr.buffer, tr->data_size)) {
		rayo_binder_user_error("%d:%d got transaction with invalid data ptr\n",
				proc->pid, thread->pid);
		return_error = BR_FAILED_REPLY;
		goto err_copy_data_failed;
	}
	if (copy_from_user(offp, (const void __user *)(uintptr_t)
			   tr->data.ptr.offsets, tr->offsets_size)) {
		rayo_binder_user_error("%d:%d got transaction with invalid offsets ptr\n",
				proc->pid, thread->pid);
		return_error = BR_FAILED_REPLY;
		goto err_copy_data_failed;
	}
	if (!IS_ALIGNED(tr->offsets_size, sizeof(rayo_binder_size_t))) {
		rayo_binder_user_error("%d:%d got transaction with invalid offsets size, %lld\n",
				proc->pid, thread->pid, (u64)tr->offsets_size);
		return_error = BR_FAILED_REPLY;
		goto err_bad_offset;
	}
	if (!IS_ALIGNED(extra_buffers_size, sizeof(u64))) {
		rayo_binder_user_error("%d:%d got transaction with unaligned buffers size, %lld\n",
				  proc->pid, thread->pid,
				  (u64)extra_buffers_size);
		return_error = BR_FAILED_REPLY;
		goto err_bad_offset;
	}
	off_end = (void *)off_start + tr->offsets_size;
	sg_bufp = (u8 *)(PTR_ALIGN(off_end, sizeof(void *)));
	sg_buf_end = sg_bufp + extra_buffers_size;
	off_min = 0;
	for (; offp < off_end; offp++) {
		struct rayo_binder_object_header *hdr;
		size_t object_size = rayo_binder_validate_object(t->buffer, *offp);

		if (object_size == 0 || *offp < off_min) {
			rayo_binder_user_error("%d:%d got transaction with invalid offset (%lld, min %lld max %lld) or object.\n",
					  proc->pid, thread->pid, (u64)*offp,
					  (u64)off_min,
					  (u64)t->buffer->data_size);
			return_error = BR_FAILED_REPLY;
			goto err_bad_offset;
		}

		hdr = (struct rayo_binder_object_header *)(t->buffer->data + *offp);
		off_min = *offp + object_size;
		switch (hdr->type) {
		case BINDER_TYPE_BINDER:
		case BINDER_TYPE_WEAK_BINDER: {
			struct flat_rayo_binder_object *fp;

			fp = to_flat_rayo_binder_object(hdr);
			ret = rayo_binder_translate_binder(fp, t, thread);
			if (ret < 0) {
				return_error = BR_FAILED_REPLY;
				goto err_translate_failed;
			}
		} break;
		case BINDER_TYPE_HANDLE:
		case BINDER_TYPE_WEAK_HANDLE: {
			struct flat_rayo_binder_object *fp;

			fp = to_flat_rayo_binder_object(hdr);
			ret = rayo_binder_translate_handle(fp, t, thread);
			if (ret < 0) {
				return_error = BR_FAILED_REPLY;
				goto err_translate_failed;
			}
		} break;

		case BINDER_TYPE_FD: {
			struct rayo_binder_fd_object *fp = to_rayo_binder_fd_object(hdr);
			int target_fd = rayo_binder_translate_fd(fp->fd, t, thread,
							    in_reply_to);

			if (target_fd < 0) {
				return_error = BR_FAILED_REPLY;
				goto err_translate_failed;
			}
			fp->pad_binder = 0;
			fp->fd = target_fd;
		} break;
		case BINDER_TYPE_FDA: {
			struct rayo_binder_fd_array_object *fda =
				to_rayo_binder_fd_array_object(hdr);
			struct rayo_binder_buffer_object *parent =
				rayo_binder_validate_ptr(t->buffer, fda->parent,
						    off_start,
						    offp - off_start);
			if (!parent) {
				rayo_binder_user_error("%d:%d got transaction with invalid parent offset or type\n",
						  proc->pid, thread->pid);
				return_error = BR_FAILED_REPLY;
				goto err_bad_parent;
			}
			if (!rayo_binder_validate_fixup(t->buffer, off_start,
						   parent, fda->parent_offset,
						   last_fixup_obj,
						   last_fixup_min_off)) {
				rayo_binder_user_error("%d:%d got transaction with out-of-order buffer fixup\n",
						  proc->pid, thread->pid);
				return_error = BR_FAILED_REPLY;
				goto err_bad_parent;
			}
			ret = rayo_binder_translate_fd_array(fda, parent, t, thread,
							in_reply_to);
			if (ret < 0) {
				return_error = BR_FAILED_REPLY;
				goto err_translate_failed;
			}
			last_fixup_obj = parent;
			last_fixup_min_off =
				fda->parent_offset + sizeof(u32) * fda->num_fds;
		} break;
		case BINDER_TYPE_PTR: {
			struct rayo_binder_buffer_object *bp =
				to_rayo_binder_buffer_object(hdr);
			size_t buf_left = sg_buf_end - sg_bufp;

			if (bp->length > buf_left) {
				rayo_binder_user_error("%d:%d got transaction with too large buffer\n",
						  proc->pid, thread->pid);
				return_error = BR_FAILED_REPLY;
				goto err_bad_offset;
			}
			if (copy_from_user(sg_bufp,
					   (const void __user *)(uintptr_t)
					   bp->buffer, bp->length)) {
				rayo_binder_user_error("%d:%d got transaction with invalid offsets ptr\n",
						  proc->pid, thread->pid);
				return_error = BR_FAILED_REPLY;
				goto err_copy_data_failed;
			}
			/* Fixup buffer pointer to target proc address space */
			bp->buffer = (uintptr_t)sg_bufp +
				target_proc->user_buffer_offset;
			sg_bufp += ALIGN(bp->length, sizeof(u64));

			ret = rayo_binder_fixup_parent(t, thread, bp, off_start,
						  offp - off_start,
						  last_fixup_obj,
						  last_fixup_min_off);
			if (ret < 0) {
				return_error = BR_FAILED_REPLY;
				goto err_translate_failed;
			}
			last_fixup_obj = bp;
			last_fixup_min_off = 0;
		} break;
		default:
			rayo_binder_user_error("%d:%d got transaction with invalid object type, %x\n",
				proc->pid, thread->pid, hdr->type);
			return_error = BR_FAILED_REPLY;
			goto err_bad_object_type;
		}
	}
	if (reply) {
		BUG_ON(t->buffer->async_transaction != 0);
		rayo_binder_pop_transaction(target_thread, in_reply_to);
	} else if (!(t->flags & TF_ONE_WAY)) {
		BUG_ON(t->buffer->async_transaction != 0);
		t->need_reply = 1;
		t->from_parent = thread->transaction_stack;
		thread->transaction_stack = t;
	} else {
		BUG_ON(target_node == NULL);
		BUG_ON(t->buffer->async_transaction != 1);
		if (target_node->has_async_transaction) {
			target_list = &target_node->async_todo;
			target_wait = NULL;
		} else
			target_node->has_async_transaction = 1;
	}
	t->work.type = BINDER_WORK_TRANSACTION;
	list_add_tail(&t->work.entry, target_list);
	tcomplete->type = BINDER_WORK_TRANSACTION_COMPLETE;
	list_add_tail(&tcomplete->entry, &thread->todo);
	if (target_wait)
		wake_up_interruptible(target_wait);
	return;

err_translate_failed:
err_bad_object_type:
err_bad_offset:
err_bad_parent:
err_copy_data_failed:
	trace_rayo_binder_transaction_failed_buffer_release(t->buffer);
	rayo_binder_transaction_buffer_release(target_proc, t->buffer, offp);
	t->buffer->transaction = NULL;
	rayo_binder_free_buf(target_proc, t->buffer);
err_rayo_binder_alloc_buf_failed:
	kfree(tcomplete);
	rayo_binder_stats_deleted(BINDER_STAT_TRANSACTION_COMPLETE);
err_alloc_tcomplete_failed:
	kfree(t);
	rayo_binder_stats_deleted(BINDER_STAT_TRANSACTION);
err_alloc_t_failed:
err_bad_call_stack:
err_empty_call_stack:
err_dead_binder:
err_invalid_target_handle:
err_no_context_mgr_node:
	rayo_binder_debug(BINDER_DEBUG_FAILED_TRANSACTION,
		     "%d:%d transaction failed %d, size %lld-%lld\n",
		     proc->pid, thread->pid, return_error,
		     (u64)tr->data_size, (u64)tr->offsets_size);

	{
		struct rayo_binder_transaction_log_entry *fe;

		fe = rayo_binder_transaction_log_add(&rayo_binder_transaction_log_failed);
		*fe = *e;
	}

	BUG_ON(thread->return_error != BR_OK);
	if (in_reply_to) {
		thread->return_error = BR_TRANSACTION_COMPLETE;
		rayo_binder_send_failed_reply(in_reply_to, return_error);
	} else
		thread->return_error = return_error;
}

static int rayo_binder_thread_write(struct rayo_binder_proc *proc,
			struct rayo_binder_thread *thread,
			rayo_binder_uintptr_t rayo_binder_buffer, size_t size,
			rayo_binder_size_t *consumed)
{
	uint32_t cmd;
	struct rayo_binder_context *context = proc->context;
	void __user *buffer = (void __user *)(uintptr_t)rayo_binder_buffer;
	void __user *ptr = buffer + *consumed;
	void __user *end = buffer + size;

	while (ptr < end && thread->return_error == BR_OK) {
		if (get_user(cmd, (uint32_t __user *)ptr))
			return -EFAULT;
		ptr += sizeof(uint32_t);
		trace_rayo_binder_command(cmd);
		if (_IOC_NR(cmd) < ARRAY_SIZE(rayo_binder_stats.bc)) {
			rayo_binder_stats.bc[_IOC_NR(cmd)]++;
			proc->stats.bc[_IOC_NR(cmd)]++;
			thread->stats.bc[_IOC_NR(cmd)]++;
		}
		switch (cmd) {
		case BC_INCREFS:
		case BC_ACQUIRE:
		case BC_RELEASE:
		case BC_DECREFS: {
			uint32_t target;
			struct rayo_binder_ref *ref;
			const char *debug_string;

			if (get_user(target, (uint32_t __user *)ptr))
				return -EFAULT;
			ptr += sizeof(uint32_t);
			if (target == 0 && context->rayo_binder_context_mgr_node &&
			    (cmd == BC_INCREFS || cmd == BC_ACQUIRE)) {
				ref = rayo_binder_get_ref_for_node(proc,
					context->rayo_binder_context_mgr_node);
				if (ref->desc != target) {
					rayo_binder_user_error("%d:%d tried to acquire reference to desc 0, got %d instead\n",
						proc->pid, thread->pid,
						ref->desc);
				}
			} else
				ref = rayo_binder_get_ref(proc, target,
						     cmd == BC_ACQUIRE ||
						     cmd == BC_RELEASE);
			if (ref == NULL) {
				rayo_binder_user_error("%d:%d refcount change on invalid ref %d\n",
					proc->pid, thread->pid, target);
				break;
			}
			switch (cmd) {
			case BC_INCREFS:
				debug_string = "IncRefs";
				rayo_binder_inc_ref(ref, 0, NULL);
				break;
			case BC_ACQUIRE:
				debug_string = "Acquire";
				rayo_binder_inc_ref(ref, 1, NULL);
				break;
			case BC_RELEASE:
				debug_string = "Release";
				rayo_binder_dec_ref(ref, 1);
				break;
			case BC_DECREFS:
			default:
				debug_string = "DecRefs";
				rayo_binder_dec_ref(ref, 0);
				break;
			}
			rayo_binder_debug(BINDER_DEBUG_USER_REFS,
				     "%d:%d %s ref %d desc %d s %d w %d for node %d\n",
				     proc->pid, thread->pid, debug_string, ref->debug_id,
				     ref->desc, ref->strong, ref->weak, ref->node->debug_id);
			break;
		}
		case BC_INCREFS_DONE:
		case BC_ACQUIRE_DONE: {
			rayo_binder_uintptr_t node_ptr;
			rayo_binder_uintptr_t cookie;
			struct rayo_binder_node *node;

			if (get_user(node_ptr, (rayo_binder_uintptr_t __user *)ptr))
				return -EFAULT;
			ptr += sizeof(rayo_binder_uintptr_t);
			if (get_user(cookie, (rayo_binder_uintptr_t __user *)ptr))
				return -EFAULT;
			ptr += sizeof(rayo_binder_uintptr_t);
			node = rayo_binder_get_node(proc, node_ptr);
			if (node == NULL) {
				rayo_binder_user_error("%d:%d %s u%016llx no match\n",
					proc->pid, thread->pid,
					cmd == BC_INCREFS_DONE ?
					"BC_INCREFS_DONE" :
					"BC_ACQUIRE_DONE",
					(u64)node_ptr);
				break;
			}
			if (cookie != node->cookie) {
				rayo_binder_user_error("%d:%d %s u%016llx node %d cookie mismatch %016llx != %016llx\n",
					proc->pid, thread->pid,
					cmd == BC_INCREFS_DONE ?
					"BC_INCREFS_DONE" : "BC_ACQUIRE_DONE",
					(u64)node_ptr, node->debug_id,
					(u64)cookie, (u64)node->cookie);
				break;
			}
			if (cmd == BC_ACQUIRE_DONE) {
				if (node->pending_strong_ref == 0) {
					rayo_binder_user_error("%d:%d BC_ACQUIRE_DONE node %d has no pending acquire request\n",
						proc->pid, thread->pid,
						node->debug_id);
					break;
				}
				node->pending_strong_ref = 0;
			} else {
				if (node->pending_weak_ref == 0) {
					rayo_binder_user_error("%d:%d BC_INCREFS_DONE node %d has no pending increfs request\n",
						proc->pid, thread->pid,
						node->debug_id);
					break;
				}
				node->pending_weak_ref = 0;
			}
			rayo_binder_dec_node(node, cmd == BC_ACQUIRE_DONE, 0);
			rayo_binder_debug(BINDER_DEBUG_USER_REFS,
				     "%d:%d %s node %d ls %d lw %d\n",
				     proc->pid, thread->pid,
				     cmd == BC_INCREFS_DONE ? "BC_INCREFS_DONE" : "BC_ACQUIRE_DONE",
				     node->debug_id, node->local_strong_refs, node->local_weak_refs);
			break;
		}
		case BC_ATTEMPT_ACQUIRE:
			rayo_binder_debug(BINDER_DEBUG_TOP_ERRORS,
				     "BC_ATTEMPT_ACQUIRE not supported\n");
			return -EINVAL;
		case BC_ACQUIRE_RESULT:
		        rayo_binder_debug(BINDER_DEBUG_TOP_ERRORS,
				     "BC_ACQUIRE_RESULT not supported\n");
			return -EINVAL;

		case BC_FREE_BUFFER: {
			rayo_binder_uintptr_t data_ptr;
			struct rayo_binder_buffer *buffer;

			if (get_user(data_ptr, (rayo_binder_uintptr_t __user *)ptr))
				return -EFAULT;
			ptr += sizeof(rayo_binder_uintptr_t);

			buffer = rayo_binder_buffer_lookup(proc, data_ptr);
			if (buffer == NULL) {
				rayo_binder_user_error("%d:%d BC_FREE_BUFFER u%016llx no match\n",
					proc->pid, thread->pid, (u64)data_ptr);
				break;
			}
			if (!buffer->allow_user_free) {
				rayo_binder_user_error("%d:%d BC_FREE_BUFFER u%016llx matched unreturned buffer\n",
					proc->pid, thread->pid, (u64)data_ptr);
				break;
			}
			rayo_binder_debug(BINDER_DEBUG_FREE_BUFFER,
				     "%d:%d BC_FREE_BUFFER u%016llx found buffer %d for %s transaction\n",
				     proc->pid, thread->pid, (u64)data_ptr, buffer->debug_id,
				     buffer->transaction ? "active" : "finished");

			if (buffer->transaction) {
				buffer->transaction->buffer = NULL;
				buffer->transaction = NULL;
			}
			if (buffer->async_transaction && buffer->target_node) {
				BUG_ON(!buffer->target_node->has_async_transaction);
				if (list_empty(&buffer->target_node->async_todo))
					buffer->target_node->has_async_transaction = 0;
				else {
					list_move_tail(buffer->target_node->async_todo.next, &thread->proc->todo);
					wake_up_interruptible(&thread->proc->wait);
				}
			}
			trace_rayo_binder_transaction_buffer_release(buffer);
			rayo_binder_transaction_buffer_release(proc, buffer, NULL);
			rayo_binder_free_buf(proc, buffer);
			break;
		}

		case BC_TRANSACTION_SG:
		case BC_REPLY_SG: {
			struct rayo_binder_transaction_data_sg tr;

			if (copy_from_user(&tr, ptr, sizeof(tr)))
				return -EFAULT;
			ptr += sizeof(tr);
			rayo_binder_transaction(proc, thread, &tr.transaction_data,
					   cmd == BC_REPLY_SG, tr.buffers_size);
			break;
		}
		case BC_TRANSACTION:
		case BC_REPLY: {
			struct rayo_binder_transaction_data tr;

			if (copy_from_user(&tr, ptr, sizeof(tr)))
				return -EFAULT;
			ptr += sizeof(tr);
			rayo_binder_transaction(proc, thread, &tr,
					   cmd == BC_REPLY, 0);
			break;
		}

		case BC_REGISTER_LOOPER:
			rayo_binder_debug(BINDER_DEBUG_THREADS,
				     "%d:%d BC_REGISTER_LOOPER\n",
				     proc->pid, thread->pid);
			if (thread->looper & BINDER_LOOPER_STATE_ENTERED) {
				thread->looper |= BINDER_LOOPER_STATE_INVALID;
				rayo_binder_user_error("%d:%d ERROR: BC_REGISTER_LOOPER called after BC_ENTER_LOOPER\n",
					proc->pid, thread->pid);
			} else if (proc->requested_threads == 0) {
				thread->looper |= BINDER_LOOPER_STATE_INVALID;
				rayo_binder_user_error("%d:%d ERROR: BC_REGISTER_LOOPER called without request\n",
					proc->pid, thread->pid);
			} else {
				proc->requested_threads--;
				proc->requested_threads_started++;
			}
			thread->looper |= BINDER_LOOPER_STATE_REGISTERED;
			break;
		case BC_ENTER_LOOPER:
			rayo_binder_debug(BINDER_DEBUG_THREADS,
				     "%d:%d BC_ENTER_LOOPER\n",
				     proc->pid, thread->pid);
			if (thread->looper & BINDER_LOOPER_STATE_REGISTERED) {
				thread->looper |= BINDER_LOOPER_STATE_INVALID;
				rayo_binder_user_error("%d:%d ERROR: BC_ENTER_LOOPER called after BC_REGISTER_LOOPER\n",
					proc->pid, thread->pid);
			}
			thread->looper |= BINDER_LOOPER_STATE_ENTERED;
			break;
		case BC_EXIT_LOOPER:
			rayo_binder_debug(BINDER_DEBUG_THREADS,
				     "%d:%d BC_EXIT_LOOPER\n",
				     proc->pid, thread->pid);
			thread->looper |= BINDER_LOOPER_STATE_EXITED;
			break;

		case BC_REQUEST_DEATH_NOTIFICATION:
		case BC_CLEAR_DEATH_NOTIFICATION: {
			uint32_t target;
			rayo_binder_uintptr_t cookie;
			struct rayo_binder_ref *ref;
			struct rayo_binder_ref_death *death;

			if (get_user(target, (uint32_t __user *)ptr))
				return -EFAULT;
			ptr += sizeof(uint32_t);
			if (get_user(cookie, (rayo_binder_uintptr_t __user *)ptr))
				return -EFAULT;
			ptr += sizeof(rayo_binder_uintptr_t);
			ref = rayo_binder_get_ref(proc, target, false);
			if (ref == NULL) {
				rayo_binder_user_error("%d:%d %s invalid ref %d\n",
					proc->pid, thread->pid,
					cmd == BC_REQUEST_DEATH_NOTIFICATION ?
					"BC_REQUEST_DEATH_NOTIFICATION" :
					"BC_CLEAR_DEATH_NOTIFICATION",
					target);
				break;
			}

			rayo_binder_debug(BINDER_DEBUG_DEATH_NOTIFICATION,
				     "%d:%d %s %016llx ref %d desc %d s %d w %d for node %d\n",
				     proc->pid, thread->pid,
				     cmd == BC_REQUEST_DEATH_NOTIFICATION ?
				     "BC_REQUEST_DEATH_NOTIFICATION" :
				     "BC_CLEAR_DEATH_NOTIFICATION",
				     (u64)cookie, ref->debug_id, ref->desc,
				     ref->strong, ref->weak, ref->node->debug_id);

			if (cmd == BC_REQUEST_DEATH_NOTIFICATION) {
				if (ref->death) {
					rayo_binder_user_error("%d:%d BC_REQUEST_DEATH_NOTIFICATION death notification already set\n",
						proc->pid, thread->pid);
					break;
				}
				death = kzalloc(sizeof(*death), GFP_KERNEL);
				if (death == NULL) {
					thread->return_error = BR_ERROR;
					rayo_binder_debug(BINDER_DEBUG_FAILED_TRANSACTION,
						     "%d:%d BC_REQUEST_DEATH_NOTIFICATION failed\n",
						     proc->pid, thread->pid);
					break;
				}
				rayo_binder_stats_created(BINDER_STAT_DEATH);
				INIT_LIST_HEAD(&death->work.entry);
				death->cookie = cookie;
				ref->death = death;
				if (ref->node->proc == NULL) {
					ref->death->work.type = BINDER_WORK_DEAD_BINDER;
					if (thread->looper & (BINDER_LOOPER_STATE_REGISTERED | BINDER_LOOPER_STATE_ENTERED)) {
						list_add_tail(&ref->death->work.entry, &thread->todo);
					} else {
						list_add_tail(&ref->death->work.entry, &proc->todo);
						wake_up_interruptible(&proc->wait);
					}
				}
			} else {
				if (ref->death == NULL) {
					rayo_binder_user_error("%d:%d BC_CLEAR_DEATH_NOTIFICATION death notification not active\n",
						proc->pid, thread->pid);
					break;
				}
				death = ref->death;
				if (death->cookie != cookie) {
					rayo_binder_user_error("%d:%d BC_CLEAR_DEATH_NOTIFICATION death notification cookie mismatch %016llx != %016llx\n",
						proc->pid, thread->pid,
						(u64)death->cookie, (u64)cookie);
					break;
				}
				ref->death = NULL;
				if (list_empty(&death->work.entry)) {
					death->work.type = BINDER_WORK_CLEAR_DEATH_NOTIFICATION;
					if (thread->looper & (BINDER_LOOPER_STATE_REGISTERED | BINDER_LOOPER_STATE_ENTERED)) {
						list_add_tail(&death->work.entry, &thread->todo);
					} else {
						list_add_tail(&death->work.entry, &proc->todo);
						wake_up_interruptible(&proc->wait);
					}
				} else {
					BUG_ON(death->work.type != BINDER_WORK_DEAD_BINDER);
					death->work.type = BINDER_WORK_DEAD_BINDER_AND_CLEAR;
				}
			}
		} break;
		case BC_DEAD_BINDER_DONE: {
			struct rayo_binder_work *w;
			rayo_binder_uintptr_t cookie;
			struct rayo_binder_ref_death *death = NULL;

			if (get_user(cookie, (rayo_binder_uintptr_t __user *)ptr))
				return -EFAULT;

			ptr += sizeof(cookie);
			list_for_each_entry(w, &proc->delivered_death, entry) {
				struct rayo_binder_ref_death *tmp_death = container_of(w, struct rayo_binder_ref_death, work);

				if (tmp_death->cookie == cookie) {
					death = tmp_death;
					break;
				}
			}
			rayo_binder_debug(BINDER_DEBUG_DEAD_BINDER,
				     "%d:%d BC_DEAD_BINDER_DONE %016llx found %pK\n",
				     proc->pid, thread->pid, (u64)cookie, death);
			if (death == NULL) {
				rayo_binder_user_error("%d:%d BC_DEAD_BINDER_DONE %016llx not found\n",
					proc->pid, thread->pid, (u64)cookie);
				break;
			}

			list_del_init(&death->work.entry);
			if (death->work.type == BINDER_WORK_DEAD_BINDER_AND_CLEAR) {
				death->work.type = BINDER_WORK_CLEAR_DEATH_NOTIFICATION;
				if (thread->looper & (BINDER_LOOPER_STATE_REGISTERED | BINDER_LOOPER_STATE_ENTERED)) {
					list_add_tail(&death->work.entry, &thread->todo);
				} else {
					list_add_tail(&death->work.entry, &proc->todo);
					wake_up_interruptible(&proc->wait);
				}
			}
		} break;

		default:
			rayo_binder_debug(BINDER_DEBUG_TOP_ERRORS,
				     "%d:%d unknown command %d\n",
				     proc->pid, thread->pid, cmd);
			return -EINVAL;
		}
		*consumed = ptr - buffer;
	}
	return 0;
}

static void rayo_binder_stat_br(struct rayo_binder_proc *proc,
			   struct rayo_binder_thread *thread, uint32_t cmd)
{
	trace_rayo_binder_return(cmd);
	if (_IOC_NR(cmd) < ARRAY_SIZE(rayo_binder_stats.br)) {
		rayo_binder_stats.br[_IOC_NR(cmd)]++;
		proc->stats.br[_IOC_NR(cmd)]++;
		thread->stats.br[_IOC_NR(cmd)]++;
	}
}

static int rayo_binder_has_proc_work(struct rayo_binder_proc *proc,
				struct rayo_binder_thread *thread)
{
	return !list_empty(&proc->todo) ||
		(thread->looper & BINDER_LOOPER_STATE_NEED_RETURN);
}

static int rayo_binder_has_thread_work(struct rayo_binder_thread *thread)
{
	return !list_empty(&thread->todo) || thread->return_error != BR_OK ||
		(thread->looper & BINDER_LOOPER_STATE_NEED_RETURN);
}

static int rayo_binder_thread_read(struct rayo_binder_proc *proc,
			      struct rayo_binder_thread *thread,
			      rayo_binder_uintptr_t rayo_binder_buffer, size_t size,
			      rayo_binder_size_t *consumed, int non_block)
{
	void __user *buffer = (void __user *)(uintptr_t)rayo_binder_buffer;
	void __user *ptr = buffer + *consumed;
	void __user *end = buffer + size;

	int ret = 0;
	int wait_for_proc_work;

	if (*consumed == 0) {
		if (put_user(BR_NOOP, (uint32_t __user *)ptr))
			return -EFAULT;
		ptr += sizeof(uint32_t);
	}

retry:
	wait_for_proc_work = thread->transaction_stack == NULL &&
				list_empty(&thread->todo);

	if (thread->return_error != BR_OK && ptr < end) {
		if (thread->return_error2 != BR_OK) {
			if (put_user(thread->return_error2, (uint32_t __user *)ptr))
				return -EFAULT;
			ptr += sizeof(uint32_t);
			rayo_binder_stat_br(proc, thread, thread->return_error2);
			if (ptr == end)
				goto done;
			thread->return_error2 = BR_OK;
		}
		if (put_user(thread->return_error, (uint32_t __user *)ptr))
			return -EFAULT;
		ptr += sizeof(uint32_t);
		rayo_binder_stat_br(proc, thread, thread->return_error);
		thread->return_error = BR_OK;
		goto done;
	}


	thread->looper |= BINDER_LOOPER_STATE_WAITING;
	if (wait_for_proc_work)
		proc->ready_threads++;

	rayo_binder_unlock(__func__);

	trace_rayo_binder_wait_for_work(wait_for_proc_work,
				   !!thread->transaction_stack,
				   !list_empty(&thread->todo));
	if (wait_for_proc_work) {
		if (!(thread->looper & (BINDER_LOOPER_STATE_REGISTERED |
					BINDER_LOOPER_STATE_ENTERED))) {
			rayo_binder_user_error("%d:%d ERROR: Thread waiting for process work before calling BC_REGISTER_LOOPER or BC_ENTER_LOOPER (state %x)\n",
				proc->pid, thread->pid, thread->looper);
			wait_event_interruptible(rayo_binder_user_error_wait,
						 rayo_binder_stop_on_user_error < 2);
		}
		rayo_binder_set_nice(proc->default_priority);
		if (non_block) {
			if (!rayo_binder_has_proc_work(proc, thread))
				ret = -EAGAIN;
		} else
			ret = wait_event_freezable_exclusive(proc->wait, rayo_binder_has_proc_work(proc, thread));
	} else {
		if (non_block) {
			if (!rayo_binder_has_thread_work(thread))
				ret = -EAGAIN;
		} else
			ret = wait_event_freezable(thread->wait, rayo_binder_has_thread_work(thread));
	}

	rayo_binder_lock(__func__);

	if (wait_for_proc_work)
		proc->ready_threads--;
	thread->looper &= ~BINDER_LOOPER_STATE_WAITING;

	if (ret)
		return ret;

	while (1) {
		uint32_t cmd;
		struct rayo_binder_transaction_data tr;
		struct rayo_binder_work *w;
		struct rayo_binder_transaction *t = NULL;

		if (!list_empty(&thread->todo))
			w = list_first_entry(&thread->todo, struct rayo_binder_work, entry);
		else if (!list_empty(&proc->todo) && wait_for_proc_work)
			w = list_first_entry(&proc->todo, struct rayo_binder_work, entry);
		else {
			if (ptr - buffer == 4 && !(thread->looper & BINDER_LOOPER_STATE_NEED_RETURN)) /* no data added */
				goto retry;
			break;
		}

		if (end - ptr < sizeof(tr) + 4)
			break;

		switch (w->type) {
		case BINDER_WORK_TRANSACTION: {
			t = container_of(w, struct rayo_binder_transaction, work);
		} break;
		case BINDER_WORK_TRANSACTION_COMPLETE: {
			cmd = BR_TRANSACTION_COMPLETE;
			if (put_user(cmd, (uint32_t __user *)ptr))
				return -EFAULT;
			ptr += sizeof(uint32_t);

			rayo_binder_stat_br(proc, thread, cmd);
			rayo_binder_debug(BINDER_DEBUG_TRANSACTION_COMPLETE,
				     "%d:%d BR_TRANSACTION_COMPLETE\n",
				     proc->pid, thread->pid);

			list_del(&w->entry);
			kfree(w);
			rayo_binder_stats_deleted(BINDER_STAT_TRANSACTION_COMPLETE);
		} break;
		case BINDER_WORK_NODE: {
			struct rayo_binder_node *node = container_of(w, struct rayo_binder_node, work);
			uint32_t cmd = BR_NOOP;
			const char *cmd_name;
			int strong = node->internal_strong_refs || node->local_strong_refs;
			int weak = !hlist_empty(&node->refs) || node->local_weak_refs || strong;

			if (weak && !node->has_weak_ref) {
				cmd = BR_INCREFS;
				cmd_name = "BR_INCREFS";
				node->has_weak_ref = 1;
				node->pending_weak_ref = 1;
				node->local_weak_refs++;
			} else if (strong && !node->has_strong_ref) {
				cmd = BR_ACQUIRE;
				cmd_name = "BR_ACQUIRE";
				node->has_strong_ref = 1;
				node->pending_strong_ref = 1;
				node->local_strong_refs++;
			} else if (!strong && node->has_strong_ref) {
				cmd = BR_RELEASE;
				cmd_name = "BR_RELEASE";
				node->has_strong_ref = 0;
			} else if (!weak && node->has_weak_ref) {
				cmd = BR_DECREFS;
				cmd_name = "BR_DECREFS";
				node->has_weak_ref = 0;
			}
			if (cmd != BR_NOOP) {
				if (put_user(cmd, (uint32_t __user *)ptr))
					return -EFAULT;
				ptr += sizeof(uint32_t);
				if (put_user(node->ptr,
					     (rayo_binder_uintptr_t __user *)ptr))
					return -EFAULT;
				ptr += sizeof(rayo_binder_uintptr_t);
				if (put_user(node->cookie,
					     (rayo_binder_uintptr_t __user *)ptr))
					return -EFAULT;
				ptr += sizeof(rayo_binder_uintptr_t);

				rayo_binder_stat_br(proc, thread, cmd);
				rayo_binder_debug(BINDER_DEBUG_USER_REFS,
					     "%d:%d %s %d u%016llx c%016llx\n",
					     proc->pid, thread->pid, cmd_name,
					     node->debug_id,
					     (u64)node->ptr, (u64)node->cookie);
			} else {
				list_del_init(&w->entry);
				if (!weak && !strong) {
					rayo_binder_debug(BINDER_DEBUG_INTERNAL_REFS,
						     "%d:%d node %d u%016llx c%016llx deleted\n",
						     proc->pid, thread->pid, node->debug_id,
						     (u64)node->ptr, (u64)node->cookie);
					rb_erase(&node->rb_node, &proc->nodes);
					kfree(node);
					rayo_binder_stats_deleted(BINDER_STAT_NODE);
				} else {
					rayo_binder_debug(BINDER_DEBUG_INTERNAL_REFS,
						     "%d:%d node %d u%016llx c%016llx state unchanged\n",
						     proc->pid, thread->pid, node->debug_id,
						     (u64)node->ptr, (u64)node->cookie);
				}
			}
		} break;
		case BINDER_WORK_DEAD_BINDER:
		case BINDER_WORK_DEAD_BINDER_AND_CLEAR:
		case BINDER_WORK_CLEAR_DEATH_NOTIFICATION: {
			struct rayo_binder_ref_death *death;
			uint32_t cmd;

			death = container_of(w, struct rayo_binder_ref_death, work);
			if (w->type == BINDER_WORK_CLEAR_DEATH_NOTIFICATION)
				cmd = BR_CLEAR_DEATH_NOTIFICATION_DONE;
			else
				cmd = BR_DEAD_BINDER;
			if (put_user(cmd, (uint32_t __user *)ptr))
				return -EFAULT;
			ptr += sizeof(uint32_t);
			if (put_user(death->cookie,
				     (rayo_binder_uintptr_t __user *)ptr))
				return -EFAULT;
			ptr += sizeof(rayo_binder_uintptr_t);
			rayo_binder_stat_br(proc, thread, cmd);
			rayo_binder_debug(BINDER_DEBUG_DEATH_NOTIFICATION,
				     "%d:%d %s %016llx\n",
				      proc->pid, thread->pid,
				      cmd == BR_DEAD_BINDER ?
				      "BR_DEAD_BINDER" :
				      "BR_CLEAR_DEATH_NOTIFICATION_DONE",
				      (u64)death->cookie);

			if (w->type == BINDER_WORK_CLEAR_DEATH_NOTIFICATION) {
				list_del(&w->entry);
				kfree(death);
				rayo_binder_stats_deleted(BINDER_STAT_DEATH);
			} else
				list_move(&w->entry, &proc->delivered_death);
			if (cmd == BR_DEAD_BINDER)
				goto done; /* DEAD_BINDER notifications can cause transactions */
		} break;
		}

		if (!t)
			continue;

		BUG_ON(t->buffer == NULL);
		if (t->buffer->target_node) {
			struct rayo_binder_node *target_node = t->buffer->target_node;

			tr.target.ptr = target_node->ptr;
			tr.cookie =  target_node->cookie;
			t->saved_priority = task_nice(current);
			if (t->priority < target_node->min_priority &&
			    !(t->flags & TF_ONE_WAY))
				rayo_binder_set_nice(t->priority);
			else if (!(t->flags & TF_ONE_WAY) ||
				 t->saved_priority > target_node->min_priority)
				rayo_binder_set_nice(target_node->min_priority);
			cmd = BR_TRANSACTION;
		} else {
			tr.target.ptr = 0;
			tr.cookie = 0;
			cmd = BR_REPLY;
		}
		tr.code = t->code;
		tr.flags = t->flags;
		tr.sender_euid = from_kuid(current_user_ns(), t->sender_euid);

		if (t->from) {
			struct task_struct *sender = t->from->proc->tsk;

			tr.sender_pid = task_tgid_nr_ns(sender,
							task_active_pid_ns(current));
		} else {
			tr.sender_pid = 0;
		}

		tr.data_size = t->buffer->data_size;
		tr.offsets_size = t->buffer->offsets_size;
		tr.data.ptr.buffer = (rayo_binder_uintptr_t)(
					(uintptr_t)t->buffer->data +
					proc->user_buffer_offset);
		tr.data.ptr.offsets = tr.data.ptr.buffer +
					ALIGN(t->buffer->data_size,
					    sizeof(void *));

		if (put_user(cmd, (uint32_t __user *)ptr))
			return -EFAULT;
		ptr += sizeof(uint32_t);
		if (copy_to_user(ptr, &tr, sizeof(tr)))
			return -EFAULT;
		ptr += sizeof(tr);

		trace_rayo_binder_transaction_received(t);
		rayo_binder_stat_br(proc, thread, cmd);
		rayo_binder_debug(BINDER_DEBUG_TRANSACTION,
			     "%d:%d %s %d %d:%d, cmd %d size %zd-%zd ptr %016llx-%016llx\n",
			     proc->pid, thread->pid,
			     (cmd == BR_TRANSACTION) ? "BR_TRANSACTION" :
			     "BR_REPLY",
			     t->debug_id, t->from ? t->from->proc->pid : 0,
			     t->from ? t->from->pid : 0, cmd,
			     t->buffer->data_size, t->buffer->offsets_size,
			     (u64)tr.data.ptr.buffer, (u64)tr.data.ptr.offsets);

		list_del(&t->work.entry);
		t->buffer->allow_user_free = 1;
		if (cmd == BR_TRANSACTION && !(t->flags & TF_ONE_WAY)) {
			t->to_parent = thread->transaction_stack;
			t->to_thread = thread;
			thread->transaction_stack = t;
		} else {
			t->buffer->transaction = NULL;
			kfree(t);
			rayo_binder_stats_deleted(BINDER_STAT_TRANSACTION);
		}
		break;
	}

done:

	*consumed = ptr - buffer;
	if (proc->requested_threads + proc->ready_threads == 0 &&
	    proc->requested_threads_started < proc->max_threads &&
	    (thread->looper & (BINDER_LOOPER_STATE_REGISTERED |
	     BINDER_LOOPER_STATE_ENTERED)) /* the user-space code fails to */
	     /*spawn a new thread if we leave this out */) {
		proc->requested_threads++;
		rayo_binder_debug(BINDER_DEBUG_THREADS,
			     "%d:%d BR_SPAWN_LOOPER\n",
			     proc->pid, thread->pid);
		if (put_user(BR_SPAWN_LOOPER, (uint32_t __user *)buffer))
			return -EFAULT;
		rayo_binder_stat_br(proc, thread, BR_SPAWN_LOOPER);
	}
	return 0;
}

static void rayo_binder_release_work(struct list_head *list)
{
	struct rayo_binder_work *w;

	while (!list_empty(list)) {
		w = list_first_entry(list, struct rayo_binder_work, entry);
		list_del_init(&w->entry);
		switch (w->type) {
		case BINDER_WORK_TRANSACTION: {
			struct rayo_binder_transaction *t;

			t = container_of(w, struct rayo_binder_transaction, work);
			if (t->buffer->target_node &&
			    !(t->flags & TF_ONE_WAY)) {
				rayo_binder_send_failed_reply(t, BR_DEAD_REPLY);
			} else {
				rayo_binder_debug(BINDER_DEBUG_DEAD_TRANSACTION,
					"undelivered transaction %d\n",
					t->debug_id);
				t->buffer->transaction = NULL;
				kfree(t);
				rayo_binder_stats_deleted(BINDER_STAT_TRANSACTION);
			}
		} break;
		case BINDER_WORK_TRANSACTION_COMPLETE: {
			rayo_binder_debug(BINDER_DEBUG_DEAD_TRANSACTION,
				"undelivered TRANSACTION_COMPLETE\n");
			kfree(w);
			rayo_binder_stats_deleted(BINDER_STAT_TRANSACTION_COMPLETE);
		} break;
		case BINDER_WORK_DEAD_BINDER_AND_CLEAR:
		case BINDER_WORK_CLEAR_DEATH_NOTIFICATION: {
			struct rayo_binder_ref_death *death;

			death = container_of(w, struct rayo_binder_ref_death, work);
			rayo_binder_debug(BINDER_DEBUG_DEAD_TRANSACTION,
				"undelivered death notification, %016llx\n",
				(u64)death->cookie);
			kfree(death);
			rayo_binder_stats_deleted(BINDER_STAT_DEATH);
		} break;
		default:
			pr_err("unexpected work type, %d, not freed\n",
			       w->type);
			break;
		}
	}

}

static struct rayo_binder_thread *rayo_binder_get_thread(struct rayo_binder_proc *proc)
{
	struct rayo_binder_thread *thread = NULL;
	struct rb_node *parent = NULL;
	struct rb_node **p = &proc->threads.rb_node;

	while (*p) {
		parent = *p;
		thread = rb_entry(parent, struct rayo_binder_thread, rb_node);

		if (current->pid < thread->pid)
			p = &(*p)->rb_left;
		else if (current->pid > thread->pid)
			p = &(*p)->rb_right;
		else
			break;
	}
	if (*p == NULL) {
		thread = kzalloc(sizeof(*thread), GFP_KERNEL);
		if (thread == NULL)
			return NULL;
		rayo_binder_stats_created(BINDER_STAT_THREAD);
		thread->proc = proc;
		thread->pid = current->pid;
		init_waitqueue_head(&thread->wait);
		INIT_LIST_HEAD(&thread->todo);
		rb_link_node(&thread->rb_node, parent, p);
		rb_insert_color(&thread->rb_node, &proc->threads);
		thread->looper |= BINDER_LOOPER_STATE_NEED_RETURN;
		thread->return_error = BR_OK;
		thread->return_error2 = BR_OK;
	}
	return thread;
}

static int rayo_binder_free_thread(struct rayo_binder_proc *proc,
			      struct rayo_binder_thread *thread)
{
	struct rayo_binder_transaction *t;
	struct rayo_binder_transaction *send_reply = NULL;
	int active_transactions = 0;

	rb_erase(&thread->rb_node, &proc->threads);
	t = thread->transaction_stack;
	if (t && t->to_thread == thread)
		send_reply = t;
	while (t) {
		active_transactions++;
		rayo_binder_debug(BINDER_DEBUG_DEAD_TRANSACTION,
			     "release %d:%d transaction %d %s, still active\n",
			      proc->pid, thread->pid,
			     t->debug_id,
			     (t->to_thread == thread) ? "in" : "out");

		if (t->to_thread == thread) {
			t->to_proc = NULL;
			t->to_thread = NULL;
			if (t->buffer) {
				t->buffer->transaction = NULL;
				t->buffer = NULL;
			}
			t = t->to_parent;
		} else if (t->from == thread) {
			t->from = NULL;
			t = t->from_parent;
		} else
			BUG();
	}
	if (send_reply)
		rayo_binder_send_failed_reply(send_reply, BR_DEAD_REPLY);
	rayo_binder_release_work(&thread->todo);
	kfree(thread);
	rayo_binder_stats_deleted(BINDER_STAT_THREAD);
	return active_transactions;
}

static unsigned int rayo_binder_poll(struct file *filp,
				struct poll_table_struct *wait)
{
	struct rayo_binder_proc *proc = filp->private_data;
	struct rayo_binder_thread *thread = NULL;
	int wait_for_proc_work;

	rayo_binder_lock(__func__);

	thread = rayo_binder_get_thread(proc);

	wait_for_proc_work = thread->transaction_stack == NULL &&
		list_empty(&thread->todo) && thread->return_error == BR_OK;

	rayo_binder_unlock(__func__);

	if (wait_for_proc_work) {
		if (rayo_binder_has_proc_work(proc, thread))
			return POLLIN;
		poll_wait(filp, &proc->wait, wait);
		if (rayo_binder_has_proc_work(proc, thread))
			return POLLIN;
	} else {
		if (rayo_binder_has_thread_work(thread))
			return POLLIN;
		poll_wait(filp, &thread->wait, wait);
		if (rayo_binder_has_thread_work(thread))
			return POLLIN;
	}
	return 0;
}

static long rayo_binder_ioctl(struct file *filp, unsigned int cmd, unsigned long arg)
{
	int ret;
	struct rayo_binder_proc *proc = filp->private_data;
	struct rayo_binder_context *context = proc->context;
	struct rayo_binder_thread *thread;
	unsigned int size = _IOC_SIZE(cmd);
	void __user *ubuf = (void __user *)arg;

	/*pr_info("rayo_binder_ioctl: %d:%d %x %lx\n", proc->pid, current->pid, cmd, arg);*/

	trace_rayo_binder_ioctl(cmd, arg);

	ret = wait_event_interruptible(rayo_binder_user_error_wait, rayo_binder_stop_on_user_error < 2);
	if (ret)
		goto err_unlocked;

	rayo_binder_lock(__func__);
	thread = rayo_binder_get_thread(proc);
	if (thread == NULL) {
		ret = -ENOMEM;
		goto err;
	}

	switch (cmd) {
	case BINDER_WRITE_READ: {
		struct rayo_binder_write_read bwr;

		if (size != sizeof(struct rayo_binder_write_read)) {
			ret = -EINVAL;
			goto err;
		}
		if (copy_from_user(&bwr, ubuf, sizeof(bwr))) {
			ret = -EFAULT;
			goto err;
		}
		rayo_binder_debug(BINDER_DEBUG_READ_WRITE,
			     "%d:%d write %lld at %016llx, read %lld at %016llx\n",
			     proc->pid, thread->pid,
			     (u64)bwr.write_size, (u64)bwr.write_buffer,
			     (u64)bwr.read_size, (u64)bwr.read_buffer);

		if (bwr.write_size > 0) {
			ret = rayo_binder_thread_write(proc, thread, bwr.write_buffer, bwr.write_size, &bwr.write_consumed);
			trace_rayo_binder_write_done(ret);
			if (ret < 0) {
				bwr.read_consumed = 0;
				if (copy_to_user(ubuf, &bwr, sizeof(bwr)))
					ret = -EFAULT;
				goto err;
			}
		}
		if (bwr.read_size > 0) {
			ret = rayo_binder_thread_read(proc, thread, bwr.read_buffer, bwr.read_size, &bwr.read_consumed, filp->f_flags & O_NONBLOCK);
			trace_rayo_binder_read_done(ret);
			if (!list_empty(&proc->todo))
				wake_up_interruptible(&proc->wait);
			if (ret < 0) {
				if (copy_to_user(ubuf, &bwr, sizeof(bwr)))
					ret = -EFAULT;
				goto err;
			}
		}
		rayo_binder_debug(BINDER_DEBUG_READ_WRITE,
			     "%d:%d wrote %lld of %lld, read return %lld of %lld\n",
			     proc->pid, thread->pid,
			     (u64)bwr.write_consumed, (u64)bwr.write_size,
			     (u64)bwr.read_consumed, (u64)bwr.read_size);
		if (copy_to_user(ubuf, &bwr, sizeof(bwr))) {
			ret = -EFAULT;
			goto err;
		}
		break;
	}
	case BINDER_SET_MAX_THREADS:
		if (copy_from_user(&proc->max_threads, ubuf, sizeof(proc->max_threads))) {
			ret = -EINVAL;
			goto err;
		}
		break;
	case BINDER_SET_CONTEXT_MGR:
		if (context->rayo_binder_context_mgr_node) {
			rayo_binder_debug(BINDER_DEBUG_TOP_ERRORS,
				     "BINDER_SET_CONTEXT_MGR already set\n");
			ret = -EBUSY;
			goto err;
		}
		ret = security_binder_set_context_mgr(proc->tsk);
		if (ret < 0)
			goto err;
		if (uid_valid(context->rayo_binder_context_mgr_uid)) {
			if (!uid_eq(context->rayo_binder_context_mgr_uid, current_euid())) {
				rayo_binder_debug(BINDER_DEBUG_TOP_ERRORS,
					     "BINDER_SET_CONTEXT_MGR bad uid %d != %d\n",
					     from_kuid(&init_user_ns, current_euid()),
					     from_kuid(&init_user_ns, context->rayo_binder_context_mgr_uid));
				ret = -EPERM;
				goto err;
			}
		} else
			context->rayo_binder_context_mgr_uid = current->cred->euid;
		context->rayo_binder_context_mgr_node = rayo_binder_new_node(proc, 0, 0);
		if (!context->rayo_binder_context_mgr_node) {
			ret = -ENOMEM;
			goto err;
		}
		context->rayo_binder_context_mgr_node->local_weak_refs++;
		context->rayo_binder_context_mgr_node->local_strong_refs++;
		context->rayo_binder_context_mgr_node->has_strong_ref = 1;
		context->rayo_binder_context_mgr_node->has_weak_ref = 1;
		break;
	case BINDER_THREAD_EXIT:
		rayo_binder_debug(BINDER_DEBUG_THREADS, "%d:%d exit\n",
			     proc->pid, thread->pid);
		rayo_binder_free_thread(proc, thread);
		thread = NULL;
		break;
	case BINDER_VERSION:
		if (size != sizeof(struct rayo_binder_version)) {
			ret = -EINVAL;
			goto err;
		}
		if (put_user(BINDER_CURRENT_PROTOCOL_VERSION, &((struct rayo_binder_version *)ubuf)->protocol_version)) {
			ret = -EINVAL;
			goto err;
		}
		break;
	default:
		ret = -EINVAL;
		goto err;
	}
	ret = 0;
err:
	if (thread)
		thread->looper &= ~BINDER_LOOPER_STATE_NEED_RETURN;
	rayo_binder_unlock(__func__);
	wait_event_interruptible(rayo_binder_user_error_wait, rayo_binder_stop_on_user_error < 2);
	if (ret && ret != -ERESTARTSYS)
		rayo_binder_debug(BINDER_DEBUG_TOP_ERRORS,
			     "%d:%d ioctl %x %lx returned %d\n",
			     proc->pid, current->pid, cmd, arg, ret);
err_unlocked:
	trace_rayo_binder_ioctl_done(ret);
	return ret;
}

static void rayo_binder_vma_open(struct vm_area_struct *vma)
{
	struct rayo_binder_proc *proc = vma->vm_private_data;

	rayo_binder_debug(BINDER_DEBUG_OPEN_CLOSE,
		     "%d open vm area %lx-%lx (%ld K) vma %lx pagep %lx\n",
		     proc->pid, vma->vm_start, vma->vm_end,
		     (vma->vm_end - vma->vm_start) / SZ_1K, vma->vm_flags,
		     (unsigned long)pgprot_val(vma->vm_page_prot));
}

static void rayo_binder_vma_close(struct vm_area_struct *vma)
{
	struct rayo_binder_proc *proc = vma->vm_private_data;

	rayo_binder_debug(BINDER_DEBUG_OPEN_CLOSE,
		     "%d close vm area %lx-%lx (%ld K) vma %lx pagep %lx\n",
		     proc->pid, vma->vm_start, vma->vm_end,
		     (vma->vm_end - vma->vm_start) / SZ_1K, vma->vm_flags,
		     (unsigned long)pgprot_val(vma->vm_page_prot));
	proc->vma = NULL;
	proc->vma_vm_mm = NULL;
	rayo_binder_defer_work(proc, BINDER_DEFERRED_PUT_FILES);
}

static int rayo_binder_vm_fault(struct vm_area_struct *vma, struct vm_fault *vmf)
{
	return VM_FAULT_SIGBUS;
}

static struct vm_operations_struct rayo_binder_vm_ops = {
	.open = rayo_binder_vma_open,
	.close = rayo_binder_vma_close,
	.fault = rayo_binder_vm_fault,
};

static int rayo_binder_mmap(struct file *filp, struct vm_area_struct *vma)
{
	int ret;
	struct vm_struct *area;
	struct rayo_binder_proc *proc = filp->private_data;
	const char *failure_string;
	struct rayo_binder_buffer *buffer;

	if (proc->tsk != current->group_leader)
		return -EINVAL;

	if ((vma->vm_end - vma->vm_start) > SZ_4M)
		vma->vm_end = vma->vm_start + SZ_4M;


#ifdef CONFIG_TIMA_RKP
	if (vma->vm_end - vma->vm_start) {
		/* iommu optimization- needs to be turned ON from
		* the tz side.
		*/
		cpu_v7_tima_iommu_opt(vma->vm_start, vma->vm_end, (unsigned long)vma->vm_mm->pgd);
		__asm__ __volatile__ (
		"mcr    p15, 0, r0, c8, c3, 0\n"
		"dsb\n"
		"isb\n");
	}
#endif
	rayo_binder_debug(BINDER_DEBUG_OPEN_CLOSE,
		     "rayo_binder_mmap: %d %lx-%lx (%ld K) vma %lx pagep %lx\n",
		     proc->pid, vma->vm_start, vma->vm_end,
		     (vma->vm_end - vma->vm_start) / SZ_1K, vma->vm_flags,
		     (unsigned long)pgprot_val(vma->vm_page_prot));

	if (vma->vm_flags & FORBIDDEN_MMAP_FLAGS) {
		ret = -EPERM;
		failure_string = "bad vm_flags";
		goto err_bad_arg;
	}
	vma->vm_flags = (vma->vm_flags | VM_DONTCOPY) & ~VM_MAYWRITE;

	mutex_lock(&rayo_binder_mmap_lock);
	if (proc->buffer) {
		ret = -EBUSY;
		failure_string = "already mapped";
		goto err_already_mapped;
	}

	area = get_vm_area(vma->vm_end - vma->vm_start, VM_IOREMAP);
	if (area == NULL) {
		ret = -ENOMEM;
		failure_string = "get_vm_area";
		goto err_get_vm_area_failed;
	}
	proc->buffer = area->addr;
	proc->user_buffer_offset = vma->vm_start - (uintptr_t)proc->buffer;
	mutex_unlock(&rayo_binder_mmap_lock);

#ifdef CONFIG_CPU_CACHE_VIPT
	if (cache_is_vipt_aliasing()) {
		while (CACHE_COLOUR((vma->vm_start ^ (uint32_t)proc->buffer))) {
			rayo_binder_debug(BINDER_DEBUG_TOP_ERRORS,
				     "rayo_binder_mmap: %d %lx-%lx maps %pK bad alignment\n", proc->pid, vma->vm_start, vma->vm_end, proc->buffer);
			vma->vm_start += PAGE_SIZE;
		}
	}
#endif
	if (vma->vm_end - vma->vm_start < BINDER_MIN_ALLOC) {
		ret = -EINVAL;
		failure_string = "VMA size < BINDER_MIN_ALLOC";
		goto err_vma_too_small;
	}
	proc->pages = kzalloc(sizeof(proc->pages[0]) * ((vma->vm_end - vma->vm_start) / PAGE_SIZE), GFP_KERNEL);
	if (proc->pages == NULL) {
		ret = -ENOMEM;
		failure_string = "alloc page array";
		goto err_alloc_pages_failed;
	}
	proc->buffer_size = vma->vm_end - vma->vm_start;

	vma->vm_ops = &rayo_binder_vm_ops;
	vma->vm_private_data = proc;

	buffer = kzalloc(sizeof(*buffer), GFP_KERNEL);
	if (!buffer) {
		ret = -ENOMEM;
		failure_string = "alloc buffer struct";
		goto err_alloc_buf_struct_failed;
	}

	ret = __rayo_binder_update_page_range(proc, 1, proc->buffer,
					 proc->buffer + BINDER_MIN_ALLOC, vma);
	if (ret) {
		ret = -ENOMEM;
		failure_string = "alloc small buf";
		goto err_alloc_small_buf_failed;
	}
	buffer->data = proc->buffer;
	list_add(&buffer->entry, &proc->buffers);
	buffer->free = 1;
	rayo_binder_insert_free_buffer(proc, buffer);
	proc->free_async_space = proc->buffer_size / 2;
	barrier();
	proc->files = get_files_struct(current);
	proc->vma = vma;
	proc->vma_vm_mm = vma->vm_mm;

	/*pr_info("rayo_binder_mmap: %d %lx-%lx maps %pK\n",
		 proc->pid, vma->vm_start, vma->vm_end, proc->buffer);*/
	return 0;

err_alloc_small_buf_failed:
	kfree(buffer);
err_alloc_buf_struct_failed:
	kfree(proc->pages);
	proc->pages = NULL;
err_alloc_pages_failed:
err_vma_too_small:
	mutex_lock(&rayo_binder_mmap_lock);
	vfree(proc->buffer);
	proc->buffer = NULL;
err_get_vm_area_failed:
err_already_mapped:
	mutex_unlock(&rayo_binder_mmap_lock);
err_bad_arg:
	rayo_binder_debug(BINDER_DEBUG_TOP_ERRORS,
		     "rayo_binder_mmap: %d %lx-%lx %s failed %d\n",
		     proc->pid, vma->vm_start, vma->vm_end, failure_string,
		     ret);
	return ret;
}

static int rayo_binder_open(struct inode *nodp, struct file *filp)
{
	struct rayo_binder_proc *proc;
	struct rayo_binder_device *rayo_binder_dev;

	rayo_binder_debug(BINDER_DEBUG_OPEN_CLOSE, "rayo_binder_open: %d:%d\n",
		     current->group_leader->pid, current->pid);

	proc = kzalloc(sizeof(*proc), GFP_KERNEL);
	if (proc == NULL)
		return -ENOMEM;
	get_task_struct(current->group_leader);
	proc->tsk = current->group_leader;
	INIT_LIST_HEAD(&proc->todo);
	init_waitqueue_head(&proc->wait);
	proc->default_priority = task_nice(current);
	INIT_LIST_HEAD(&proc->buffers);
	rayo_binder_dev = container_of(filp->private_data, struct rayo_binder_device,
				  miscdev);
	proc->context = &rayo_binder_dev->context;

	rayo_binder_lock(__func__);

	rayo_binder_stats_created(BINDER_STAT_PROC);
	hlist_add_head(&proc->proc_node, &rayo_binder_procs);
	proc->pid = current->group_leader->pid;
	INIT_LIST_HEAD(&proc->delivered_death);
	filp->private_data = proc;

	rayo_binder_unlock(__func__);

	if (rayo_binder_debugfs_dir_entry_proc) {
		char strbuf[11];

		snprintf(strbuf, sizeof(strbuf), "%u", proc->pid);
		/*
		 * proc debug entries are shared between contexts, so
		 * this will fail if the process tries to open the driver
		 * again with a different context. The priting code will
		 * anyway print all contexts that a given PID has, so this
		 * is not a problem.
		 */
		proc->debugfs_entry = debugfs_create_file(strbuf, S_IRUGO,
			rayo_binder_debugfs_dir_entry_proc,
			(void *)(unsigned long)proc->pid,
			&rayo_binder_proc_fops);
	}

	return 0;
}

static int rayo_binder_flush(struct file *filp, fl_owner_t id)
{
	struct rayo_binder_proc *proc = filp->private_data;

	rayo_binder_defer_work(proc, BINDER_DEFERRED_FLUSH);

	return 0;
}

static void rayo_binder_deferred_flush(struct rayo_binder_proc *proc)
{
	struct rb_node *n;
	int wake_count = 0;

	for (n = rb_first(&proc->threads); n != NULL; n = rb_next(n)) {
		struct rayo_binder_thread *thread = rb_entry(n, struct rayo_binder_thread, rb_node);

		thread->looper |= BINDER_LOOPER_STATE_NEED_RETURN;
		if (thread->looper & BINDER_LOOPER_STATE_WAITING) {
			wake_up_interruptible(&thread->wait);
			wake_count++;
		}
	}
	wake_up_interruptible_all(&proc->wait);

	rayo_binder_debug(BINDER_DEBUG_OPEN_CLOSE,
		     "rayo_binder_flush: %d woke %d threads\n", proc->pid,
		     wake_count);
}

static int rayo_binder_release(struct inode *nodp, struct file *filp)
{
	struct rayo_binder_proc *proc = filp->private_data;

	debugfs_remove(proc->debugfs_entry);
	rayo_binder_defer_work(proc, BINDER_DEFERRED_RELEASE);

	return 0;
}

static void rayo_binder_deferred_release(struct rayo_binder_proc *proc)
{
	struct rayo_binder_transaction *t;
	struct rayo_binder_context *context = proc->context;
	struct rb_node *n;
	struct rayo_binder_buffer *buffer;
	int threads, nodes, incoming_refs, outgoing_refs, buffers,
		active_transactions, page_count;

	BUG_ON(proc->vma);
	BUG_ON(proc->files);

	hlist_del(&proc->proc_node);

	if (context->rayo_binder_context_mgr_node &&
	    context->rayo_binder_context_mgr_node->proc == proc) {
		rayo_binder_debug(BINDER_DEBUG_DEAD_BINDER,
			     "%s: %d context_mgr_node gone\n",
			     __func__, proc->pid);
		context->rayo_binder_context_mgr_node = NULL;
	}

	threads = 0;
	active_transactions = 0;
	while ((n = rb_first(&proc->threads))) {
		struct rayo_binder_thread *thread;

		thread = rb_entry(n, struct rayo_binder_thread, rb_node);
		threads++;
		active_transactions += rayo_binder_free_thread(proc, thread);
	}

	nodes = 0;
	incoming_refs = 0;
	while ((n = rb_first(&proc->nodes))) {
		struct rayo_binder_node *node;

		node = rb_entry(n, struct rayo_binder_node, rb_node);
		nodes++;
		rb_erase(&node->rb_node, &proc->nodes);
		list_del_init(&node->work.entry);
		rayo_binder_release_work(&node->async_todo);
		if (hlist_empty(&node->refs)) {
			kfree(node);
			rayo_binder_stats_deleted(BINDER_STAT_NODE);
		} else {
			struct rayo_binder_ref *ref;
			int death = 0;

			node->proc = NULL;
			node->local_strong_refs = 0;
			node->local_weak_refs = 0;
			hlist_add_head(&node->dead_node, &rayo_binder_dead_nodes);

			hlist_for_each_entry(ref, &node->refs, node_entry) {
				incoming_refs++;
				if (ref->death) {
					death++;
					if (list_empty(&ref->death->work.entry)) {
						ref->death->work.type = BINDER_WORK_DEAD_BINDER;
						list_add_tail(&ref->death->work.entry, &ref->proc->todo);
						wake_up_interruptible(&ref->proc->wait);
					} else
						BUG();
				}
			}
			rayo_binder_debug(BINDER_DEBUG_DEAD_BINDER,
				     "node %d now dead, refs %d, death %d\n",
				      node->debug_id, incoming_refs, death);
		}
	}

	outgoing_refs = 0;
	while ((n = rb_first(&proc->refs_by_desc))) {
		struct rayo_binder_ref *ref;

		ref = rb_entry(n, struct rayo_binder_ref, rb_node_desc);
		outgoing_refs++;
		rayo_binder_delete_ref(ref);
	}

	rayo_binder_release_work(&proc->todo);
	rayo_binder_release_work(&proc->delivered_death);

	buffers = 0;
	while ((n = rb_first(&proc->allocated_buffers))) {
		buffer = rb_entry(n, struct rayo_binder_buffer, rb_node);

		t = buffer->transaction;
		if (t) {
			t->buffer = NULL;
			buffer->transaction = NULL;
			rayo_binder_debug(BINDER_DEBUG_TOP_ERRORS,
				     "release proc %d, transaction %d, not freed\n",
				     proc->pid, t->debug_id);
			/*BUG();*/
		}

		rayo_binder_free_buf(proc, buffer);
		buffers++;
	}

	rayo_binder_stats_deleted(BINDER_STAT_PROC);

	while (!list_empty(&proc->buffers)) {
		buffer = list_first_entry(&proc->buffers,
					  struct rayo_binder_buffer, entry);
		WARN_ON(!buffer->free);

		list_del(&buffer->entry);
		WARN_ON_ONCE(!list_empty(&proc->buffers));
		kfree(buffer);
	}

	page_count = 0;
	if (proc->pages) {
		int i;

		for (i = 0; i < proc->buffer_size / PAGE_SIZE; i++) {
			void *page_addr;

			if (!proc->pages[i])
				continue;

			page_addr = proc->buffer + i * PAGE_SIZE;
			rayo_binder_debug(BINDER_DEBUG_BUFFER_ALLOC,
				     "%s: %d: page %d at %pK not freed\n",
				     __func__, proc->pid, i, page_addr);
			unmap_kernel_range((unsigned long)page_addr, PAGE_SIZE);
			__free_page(proc->pages[i]);
			page_count++;
		}
		kfree(proc->pages);
		vfree(proc->buffer);
	}

	put_task_struct(proc->tsk);

	rayo_binder_debug(BINDER_DEBUG_OPEN_CLOSE,
		     "%s: %d threads %d, nodes %d (ref %d), refs %d, active transactions %d, buffers %d, pages %d\n",
		     __func__, proc->pid, threads, nodes, incoming_refs,
		     outgoing_refs, active_transactions, buffers, page_count);

	kfree(proc);
}

static void rayo_binder_deferred_func(struct work_struct *work)
{
	struct rayo_binder_proc *proc;
	struct files_struct *files;

	int defer;

	do {
		rayo_binder_lock(__func__);
		mutex_lock(&rayo_binder_deferred_lock);
		if (!hlist_empty(&rayo_binder_deferred_list)) {
			proc = hlist_entry(rayo_binder_deferred_list.first,
					struct rayo_binder_proc, deferred_work_node);
			hlist_del_init(&proc->deferred_work_node);
			defer = proc->deferred_work;
			proc->deferred_work = 0;
		} else {
			proc = NULL;
			defer = 0;
		}
		mutex_unlock(&rayo_binder_deferred_lock);

		files = NULL;
		if (defer & BINDER_DEFERRED_PUT_FILES) {
			files = proc->files;
			if (files)
				proc->files = NULL;
		}

		if (defer & BINDER_DEFERRED_FLUSH)
			rayo_binder_deferred_flush(proc);

		if (defer & BINDER_DEFERRED_RELEASE)
			rayo_binder_deferred_release(proc); /* frees proc */

		rayo_binder_unlock(__func__);
		if (files)
			put_files_struct(files);
	} while (proc);
}
static DECLARE_WORK(rayo_binder_deferred_work, rayo_binder_deferred_func);

static void
rayo_binder_defer_work(struct rayo_binder_proc *proc, enum rayo_binder_deferred_state defer)
{
	mutex_lock(&rayo_binder_deferred_lock);
	proc->deferred_work |= defer;
	if (hlist_unhashed(&proc->deferred_work_node)) {
		hlist_add_head(&proc->deferred_work_node,
				&rayo_binder_deferred_list);
		queue_work(rayo_binder_deferred_workqueue, &rayo_binder_deferred_work);
	}
	mutex_unlock(&rayo_binder_deferred_lock);
}

static void print_rayo_binder_transaction(struct seq_file *m, const char *prefix,
				     struct rayo_binder_transaction *t)
{
	seq_printf(m,
		   "%s %d: %pK from %d:%d to %d:%d code %x flags %x pri %ld r%d",
		   prefix, t->debug_id, t,
		   t->from ? t->from->proc->pid : 0,
		   t->from ? t->from->pid : 0,
		   t->to_proc ? t->to_proc->pid : 0,
		   t->to_thread ? t->to_thread->pid : 0,
		   t->code, t->flags, t->priority, t->need_reply);
	if (t->buffer == NULL) {
		seq_puts(m, " buffer free\n");
		return;
	}
	if (t->buffer->target_node)
		seq_printf(m, " node %d",
			   t->buffer->target_node->debug_id);
	seq_printf(m, " size %zd:%zd data %pK\n",
		   t->buffer->data_size, t->buffer->offsets_size,
		   t->buffer->data);
}

static void print_rayo_binder_buffer(struct seq_file *m, const char *prefix,
				struct rayo_binder_buffer *buffer)
{
	seq_printf(m, "%s %d: %pK size %zd:%zd %s\n",
		   prefix, buffer->debug_id, buffer->data,
		   buffer->data_size, buffer->offsets_size,
		   buffer->transaction ? "active" : "delivered");
}

static void print_rayo_binder_work(struct seq_file *m, const char *prefix,
			      const char *transaction_prefix,
			      struct rayo_binder_work *w)
{
	struct rayo_binder_node *node;
	struct rayo_binder_transaction *t;

	switch (w->type) {
	case BINDER_WORK_TRANSACTION:
		t = container_of(w, struct rayo_binder_transaction, work);
		print_rayo_binder_transaction(m, transaction_prefix, t);
		break;
	case BINDER_WORK_TRANSACTION_COMPLETE:
		seq_printf(m, "%stransaction complete\n", prefix);
		break;
	case BINDER_WORK_NODE:
		node = container_of(w, struct rayo_binder_node, work);
		seq_printf(m, "%snode work %d: u%016llx c%016llx\n",
			   prefix, node->debug_id,
			   (u64)node->ptr, (u64)node->cookie);
		break;
	case BINDER_WORK_DEAD_BINDER:
		seq_printf(m, "%shas dead binder\n", prefix);
		break;
	case BINDER_WORK_DEAD_BINDER_AND_CLEAR:
		seq_printf(m, "%shas cleared dead binder\n", prefix);
		break;
	case BINDER_WORK_CLEAR_DEATH_NOTIFICATION:
		seq_printf(m, "%shas cleared death notification\n", prefix);
		break;
	default:
		seq_printf(m, "%sunknown work: type %d\n", prefix, w->type);
		break;
	}
}

static void print_rayo_binder_thread(struct seq_file *m,
				struct rayo_binder_thread *thread,
				int print_always)
{
	struct rayo_binder_transaction *t;
	struct rayo_binder_work *w;
	size_t start_pos = m->count;
	size_t header_pos;

	seq_printf(m, "  thread %d: l %02x\n", thread->pid, thread->looper);
	header_pos = m->count;
	t = thread->transaction_stack;
	while (t) {
		if (t->from == thread) {
			print_rayo_binder_transaction(m,
						 "    outgoing transaction", t);
			t = t->from_parent;
		} else if (t->to_thread == thread) {
			print_rayo_binder_transaction(m,
						 "    incoming transaction", t);
			t = t->to_parent;
		} else {
			print_rayo_binder_transaction(m, "    bad transaction", t);
			t = NULL;
		}
	}
	list_for_each_entry(w, &thread->todo, entry) {
		print_rayo_binder_work(m, "    ", "    pending transaction", w);
	}
	if (!print_always && m->count == header_pos)
		m->count = start_pos;
}

static void print_rayo_binder_node(struct seq_file *m, struct rayo_binder_node *node)
{
	struct rayo_binder_ref *ref;
	struct rayo_binder_work *w;
	int count;

	count = 0;
	hlist_for_each_entry(ref, &node->refs, node_entry)
		count++;

	seq_printf(m, "  node %d: u%016llx c%016llx hs %d hw %d ls %d lw %d is %d iw %d",
		   node->debug_id, (u64)node->ptr, (u64)node->cookie,
		   node->has_strong_ref, node->has_weak_ref,
		   node->local_strong_refs, node->local_weak_refs,
		   node->internal_strong_refs, count);
	if (count) {
		seq_puts(m, " proc");
		hlist_for_each_entry(ref, &node->refs, node_entry)
			seq_printf(m, " %d", ref->proc->pid);
	}
	seq_puts(m, "\n");
	list_for_each_entry(w, &node->async_todo, entry)
		print_rayo_binder_work(m, "    ",
				  "    pending async transaction", w);
}

static void print_rayo_binder_ref(struct seq_file *m, struct rayo_binder_ref *ref)
{
	seq_printf(m, "  ref %d: desc %d %snode %d s %d w %d d %pK\n",
		   ref->debug_id, ref->desc, ref->node->proc ? "" : "dead ",
		   ref->node->debug_id, ref->strong, ref->weak, ref->death);
}

static void print_rayo_binder_proc(struct seq_file *m,
			      struct rayo_binder_proc *proc, int print_all)
{
	struct rayo_binder_work *w;
	struct rb_node *n;
	size_t start_pos = m->count;
	size_t header_pos;

	seq_printf(m, "proc %d\n", proc->pid);
	seq_printf(m, "context %s\n", proc->context->name);
	header_pos = m->count;

	for (n = rb_first(&proc->threads); n != NULL; n = rb_next(n))
		print_rayo_binder_thread(m, rb_entry(n, struct rayo_binder_thread,
						rb_node), print_all);
	for (n = rb_first(&proc->nodes); n != NULL; n = rb_next(n)) {
		struct rayo_binder_node *node = rb_entry(n, struct rayo_binder_node,
						    rb_node);
		if (print_all || node->has_async_transaction)
			print_rayo_binder_node(m, node);
	}
	if (print_all) {
		for (n = rb_first(&proc->refs_by_desc);
		     n != NULL;
		     n = rb_next(n))
			print_rayo_binder_ref(m, rb_entry(n, struct rayo_binder_ref,
						     rb_node_desc));
	}
	for (n = rb_first(&proc->allocated_buffers); n != NULL; n = rb_next(n))
		print_rayo_binder_buffer(m, "  buffer",
				    rb_entry(n, struct rayo_binder_buffer, rb_node));
	list_for_each_entry(w, &proc->todo, entry)
		print_rayo_binder_work(m, "  ", "  pending transaction", w);
	list_for_each_entry(w, &proc->delivered_death, entry) {
		seq_puts(m, "  has delivered dead binder\n");
		break;
	}
	if (!print_all && m->count == header_pos)
		m->count = start_pos;
}

static const char *rayo_binder_return_strings[] = {
	"BR_ERROR",
	"BR_OK",
	"BR_TRANSACTION",
	"BR_REPLY",
	"BR_ACQUIRE_RESULT",
	"BR_DEAD_REPLY",
	"BR_TRANSACTION_COMPLETE",
	"BR_INCREFS",
	"BR_ACQUIRE",
	"BR_RELEASE",
	"BR_DECREFS",
	"BR_ATTEMPT_ACQUIRE",
	"BR_NOOP",
	"BR_SPAWN_LOOPER",
	"BR_FINISHED",
	"BR_DEAD_BINDER",
	"BR_CLEAR_DEATH_NOTIFICATION_DONE",
	"BR_FAILED_REPLY"
};

static const char *rayo_binder_command_strings[] = {
	"BC_TRANSACTION",
	"BC_REPLY",
	"BC_ACQUIRE_RESULT",
	"BC_FREE_BUFFER",
	"BC_INCREFS",
	"BC_ACQUIRE",
	"BC_RELEASE",
	"BC_DECREFS",
	"BC_INCREFS_DONE",
	"BC_ACQUIRE_DONE",
	"BC_ATTEMPT_ACQUIRE",
	"BC_REGISTER_LOOPER",
	"BC_ENTER_LOOPER",
	"BC_EXIT_LOOPER",
	"BC_REQUEST_DEATH_NOTIFICATION",
	"BC_CLEAR_DEATH_NOTIFICATION",
	"BC_DEAD_BINDER_DONE",
	"BC_TRANSACTION_SG",
	"BC_REPLY_SG",
};

static const char *rayo_binder_objstat_strings[] = {
	"proc",
	"thread",
	"node",
	"ref",
	"death",
	"transaction",
	"transaction_complete"
};

static void print_rayo_binder_stats(struct seq_file *m, const char *prefix,
			       struct rayo_binder_stats *stats)
{
	int i;

	BUILD_BUG_ON(ARRAY_SIZE(stats->bc) !=
		     ARRAY_SIZE(rayo_binder_command_strings));
	for (i = 0; i < ARRAY_SIZE(stats->bc); i++) {
		if (stats->bc[i])
			seq_printf(m, "%s%s: %d\n", prefix,
				   rayo_binder_command_strings[i], stats->bc[i]);
	}

	BUILD_BUG_ON(ARRAY_SIZE(stats->br) !=
		     ARRAY_SIZE(rayo_binder_return_strings));
	for (i = 0; i < ARRAY_SIZE(stats->br); i++) {
		if (stats->br[i])
			seq_printf(m, "%s%s: %d\n", prefix,
				   rayo_binder_return_strings[i], stats->br[i]);
	}

	BUILD_BUG_ON(ARRAY_SIZE(stats->obj_created) !=
		     ARRAY_SIZE(rayo_binder_objstat_strings));
	BUILD_BUG_ON(ARRAY_SIZE(stats->obj_created) !=
		     ARRAY_SIZE(stats->obj_deleted));
	for (i = 0; i < ARRAY_SIZE(stats->obj_created); i++) {
		if (stats->obj_created[i] || stats->obj_deleted[i])
			seq_printf(m, "%s%s: active %d total %d\n", prefix,
				rayo_binder_objstat_strings[i],
				stats->obj_created[i] - stats->obj_deleted[i],
				stats->obj_created[i]);
	}
}

static void print_rayo_binder_proc_stats(struct seq_file *m,
				    struct rayo_binder_proc *proc)
{
	struct rayo_binder_work *w;
	struct rb_node *n;
	int count, strong, weak;

	seq_printf(m, "proc %d\n", proc->pid);
	seq_printf(m, "context %s\n", proc->context->name);
	count = 0;
	for (n = rb_first(&proc->threads); n != NULL; n = rb_next(n))
		count++;
	seq_printf(m, "  threads: %d\n", count);
	seq_printf(m, "  requested threads: %d+%d/%d\n"
			"  ready threads %d\n"
			"  free async space %zd\n", proc->requested_threads,
			proc->requested_threads_started, proc->max_threads,
			proc->ready_threads, proc->free_async_space);
	count = 0;
	for (n = rb_first(&proc->nodes); n != NULL; n = rb_next(n))
		count++;
	seq_printf(m, "  nodes: %d\n", count);
	count = 0;
	strong = 0;
	weak = 0;
	for (n = rb_first(&proc->refs_by_desc); n != NULL; n = rb_next(n)) {
		struct rayo_binder_ref *ref = rb_entry(n, struct rayo_binder_ref,
						  rb_node_desc);
		count++;
		strong += ref->strong;
		weak += ref->weak;
	}
	seq_printf(m, "  refs: %d s %d w %d\n", count, strong, weak);

	count = 0;
	for (n = rb_first(&proc->allocated_buffers); n != NULL; n = rb_next(n))
		count++;
	seq_printf(m, "  buffers: %d\n", count);

	count = 0;
	list_for_each_entry(w, &proc->todo, entry) {
		switch (w->type) {
		case BINDER_WORK_TRANSACTION:
			count++;
			break;
		default:
			break;
		}
	}
	seq_printf(m, "  pending transactions: %d\n", count);

	print_rayo_binder_stats(m, "  ", &proc->stats);
}


static int rayo_binder_state_show(struct seq_file *m, void *unused)
{
	struct rayo_binder_proc *proc;
	struct rayo_binder_node *node;
	int do_lock = !rayo_binder_debug_no_lock;

	if (do_lock)
		rayo_binder_lock(__func__);

	seq_puts(m, "binder state:\n");

	if (!hlist_empty(&rayo_binder_dead_nodes))
		seq_puts(m, "dead nodes:\n");
	hlist_for_each_entry(node, &rayo_binder_dead_nodes, dead_node)
		print_rayo_binder_node(m, node);

	hlist_for_each_entry(proc, &rayo_binder_procs, proc_node)
		print_rayo_binder_proc(m, proc, 1);
	if (do_lock)
		rayo_binder_unlock(__func__);
	return 0;
}

static int rayo_binder_stats_show(struct seq_file *m, void *unused)
{
	struct rayo_binder_proc *proc;
	int do_lock = !rayo_binder_debug_no_lock;

	if (do_lock)
		rayo_binder_lock(__func__);

	seq_puts(m, "binder stats:\n");

	print_rayo_binder_stats(m, "", &rayo_binder_stats);

	hlist_for_each_entry(proc, &rayo_binder_procs, proc_node)
		print_rayo_binder_proc_stats(m, proc);
	if (do_lock)
		rayo_binder_unlock(__func__);
	return 0;
}

static int rayo_binder_transactions_show(struct seq_file *m, void *unused)
{
	struct rayo_binder_proc *proc;
	int do_lock = !rayo_binder_debug_no_lock;

	if (do_lock)
		rayo_binder_lock(__func__);

	seq_puts(m, "binder transactions:\n");
	hlist_for_each_entry(proc, &rayo_binder_procs, proc_node)
		print_rayo_binder_proc(m, proc, 0);
	if (do_lock)
		rayo_binder_unlock(__func__);
	return 0;
}

static int rayo_binder_proc_show(struct seq_file *m, void *unused)
{
	struct rayo_binder_proc *itr;
	int pid = (unsigned long)m->private;
	int do_lock = !rayo_binder_debug_no_lock;

	if (do_lock)
		rayo_binder_lock(__func__);

	hlist_for_each_entry(itr, &rayo_binder_procs, proc_node) {
		if (itr->pid == pid) {
			seq_puts(m, "binder proc state:\n");
			print_rayo_binder_proc(m, itr, 1);
		}
	}
	if (do_lock)
		rayo_binder_unlock(__func__);
	return 0;
}

static void print_rayo_binder_transaction_log_entry(struct seq_file *m,
					struct rayo_binder_transaction_log_entry *e)
{
	seq_printf(m,
		   "%d: %s from %d:%d to %d:%d context %s node %d handle %d size %d:%d\n",
		   e->debug_id, (e->call_type == 2) ? "reply" :
		   ((e->call_type == 1) ? "async" : "call "), e->from_proc,
		   e->from_thread, e->to_proc, e->to_thread, e->context_name,
		   e->to_node, e->target_handle, e->data_size, e->offsets_size);
}

static int rayo_binder_transaction_log_show(struct seq_file *m, void *unused)
{
	struct rayo_binder_transaction_log *log = m->private;
	int i;

	if (log->full) {
		for (i = log->next; i < ARRAY_SIZE(log->entry); i++)
			print_rayo_binder_transaction_log_entry(m, &log->entry[i]);
	}
	for (i = 0; i < log->next; i++)
		print_rayo_binder_transaction_log_entry(m, &log->entry[i]);
	return 0;
}

static const struct file_operations rayo_binder_fops = {
	.owner = THIS_MODULE,
	.poll = rayo_binder_poll,
	.unlocked_ioctl = rayo_binder_ioctl,
	.compat_ioctl = rayo_binder_ioctl,
	.mmap = rayo_binder_mmap,
	.open = rayo_binder_open,
	.flush = rayo_binder_flush,
	.release = rayo_binder_release,
};

BINDER_DEBUG_ENTRY(state);
BINDER_DEBUG_ENTRY(stats);
BINDER_DEBUG_ENTRY(transactions);
BINDER_DEBUG_ENTRY(transaction_log);

static int __init init_rayo_binder_device(const char *name)
{
	int ret;
	struct rayo_binder_device *rayo_binder_device;

	rayo_binder_device = kzalloc(sizeof(*rayo_binder_device), GFP_KERNEL);
	if (!rayo_binder_device)
		return -ENOMEM;

	rayo_binder_device->miscdev.fops = &rayo_binder_fops;
	rayo_binder_device->miscdev.minor = MISC_DYNAMIC_MINOR;
	rayo_binder_device->miscdev.name = name;

	rayo_binder_device->context.rayo_binder_context_mgr_uid = INVALID_UID;
	rayo_binder_device->context.name = name;

	ret = misc_register(&rayo_binder_device->miscdev);
	if (ret < 0) {
		kfree(rayo_binder_device);
		return ret;
	}

	hlist_add_head(&rayo_binder_device->hlist, &rayo_binder_devices);

	return ret;
}

static int __init rayo_binder_init(void)
{
	int ret;
	char *device_name, *device_names;
	struct rayo_binder_device *device;
	struct hlist_node *tmp;

	ret = rayo_resolve_originals();
	if (ret) return ret;

	rayo_binder_deferred_workqueue = create_singlethread_workqueue("binder");
	if (!rayo_binder_deferred_workqueue)
		return -ENOMEM;

	rayo_binder_debugfs_dir_entry_root = debugfs_create_dir("rayo_hwbinder", NULL);
	if (rayo_binder_debugfs_dir_entry_root)
		rayo_binder_debugfs_dir_entry_proc = debugfs_create_dir("proc",
						 rayo_binder_debugfs_dir_entry_root);

	if (rayo_binder_debugfs_dir_entry_root) {
		debugfs_create_file("state",
				    S_IRUGO,
				    rayo_binder_debugfs_dir_entry_root,
				    NULL,
				    &rayo_binder_state_fops);
		debugfs_create_file("stats",
				    S_IRUGO,
				    rayo_binder_debugfs_dir_entry_root,
				    NULL,
				    &rayo_binder_stats_fops);
		debugfs_create_file("transactions",
				    S_IRUGO,
				    rayo_binder_debugfs_dir_entry_root,
				    NULL,
				    &rayo_binder_transactions_fops);
		debugfs_create_file("transaction_log",
				    S_IRUGO,
				    rayo_binder_debugfs_dir_entry_root,
				    &rayo_binder_transaction_log,
				    &rayo_binder_transaction_log_fops);
		debugfs_create_file("failed_transaction_log",
				    S_IRUGO,
				    rayo_binder_debugfs_dir_entry_root,
				    &rayo_binder_transaction_log_failed,
				    &rayo_binder_transaction_log_fops);
	}

	/*
	 * Copy the module_parameter string, because we don't want to
	 * tokenize it in-place.
	 */
	device_names = kzalloc(strlen(rayo_binder_devices_param) + 1, GFP_KERNEL);
	if (!device_names) {
		ret = -ENOMEM;
		goto err_alloc_device_names_failed;
	}
	strcpy(device_names, rayo_binder_devices_param);

	while ((device_name = strsep(&device_names, ","))) {
		ret = init_rayo_binder_device(device_name);
		if (ret)
			goto err_init_rayo_binder_device_failed;
	}

	return ret;

err_init_rayo_binder_device_failed:
	hlist_for_each_entry_safe(device, tmp, &rayo_binder_devices, hlist) {
		misc_deregister(&device->miscdev);
		hlist_del(&device->hlist);
		kfree(device);
	}
err_alloc_device_names_failed:
	debugfs_remove_recursive(rayo_binder_debugfs_dir_entry_root);

	destroy_workqueue(rayo_binder_deferred_workqueue);

	return ret;
}

device_initcall(rayo_binder_init);

#define CREATE_TRACE_POINTS
#include "rayo_binder_trace.h"

MODULE_LICENSE("GPL v2");
