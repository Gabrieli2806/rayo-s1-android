/* Offline DWARF comparison only. Never install this object on the projector. */
#include <linux/module.h>
#include <linux/sched.h>
#include <linux/mm.h>
#include <linux/fs.h>
#include <linux/cred.h>
#include <linux/fdtable.h>
#include <linux/miscdevice.h>

struct task_struct *rayo_task_type;
struct mm_struct *rayo_mm_type;
struct vm_area_struct *rayo_vma_type;
struct file *rayo_file_type;
struct inode *rayo_inode_type;
struct cred *rayo_cred_type;
struct page *rayo_page_type;
struct files_struct *rayo_files_type;
struct thread_info *rayo_thread_info_type;

static int __init rayo_layout_init(void) { return -ENODEV; }
static void __exit rayo_layout_exit(void) {}
module_init(rayo_layout_init);
module_exit(rayo_layout_exit);
MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("Offline layout comparison; refuses to load");
