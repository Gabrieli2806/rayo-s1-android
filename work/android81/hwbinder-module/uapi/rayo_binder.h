/*
 * Copyright (C) 2008 Google, Inc.
 *
 * Based on, but no longer compatible with, the original
 * OpenBinder.org binder driver interface, which is:
 *
 * Copyright (c) 2005 Palmsource, Inc.
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

#ifndef _UAPI_LINUX_BINDER_H
#define _UAPI_LINUX_BINDER_H

#include <linux/ioctl.h>

#define B_PACK_CHARS(c1, c2, c3, c4) \
	((((c1)<<24)) | (((c2)<<16)) | (((c3)<<8)) | (c4))
#define B_TYPE_LARGE 0x85

enum {
	BINDER_TYPE_BINDER	= B_PACK_CHARS('s', 'b', '*', B_TYPE_LARGE),
	BINDER_TYPE_WEAK_BINDER	= B_PACK_CHARS('w', 'b', '*', B_TYPE_LARGE),
	BINDER_TYPE_HANDLE	= B_PACK_CHARS('s', 'h', '*', B_TYPE_LARGE),
	BINDER_TYPE_WEAK_HANDLE	= B_PACK_CHARS('w', 'h', '*', B_TYPE_LARGE),
	BINDER_TYPE_FD		= B_PACK_CHARS('f', 'd', '*', B_TYPE_LARGE),
	BINDER_TYPE_FDA		= B_PACK_CHARS('f', 'd', 'a', B_TYPE_LARGE),
	BINDER_TYPE_PTR		= B_PACK_CHARS('p', 't', '*', B_TYPE_LARGE),
};

enum {
	FLAT_BINDER_FLAG_PRIORITY_MASK = 0xff,
	FLAT_BINDER_FLAG_ACCEPTS_FDS = 0x100,
};

#ifdef BINDER_IPC_32BIT
typedef __u32 rayo_binder_size_t;
typedef __u32 rayo_binder_uintptr_t;
#else
typedef __u64 rayo_binder_size_t;
typedef __u64 rayo_binder_uintptr_t;
#endif

/**
 * struct rayo_binder_object_header - header shared by all binder metadata objects.
 * @type:	type of the object
 */
struct rayo_binder_object_header {
	__u32        type;
};

/*
 * This is the flattened representation of a Binder object for transfer
 * between processes.  The 'offsets' supplied as part of a binder transaction
 * contains offsets into the data where these structures occur.  The Binder
 * driver takes care of re-writing the structure type and data as it moves
 * between processes.
 */
struct flat_rayo_binder_object {
	struct rayo_binder_object_header	hdr;
	__u32				flags;

	/* 8 bytes of data. */
	union {
		rayo_binder_uintptr_t	binder;	/* local object */
		__u32			handle;	/* remote object */
	};

	/* extra data associated with local object */
	rayo_binder_uintptr_t	cookie;
};

/**
 * struct rayo_binder_fd_object - describes a filedescriptor to be fixed up.
 * @hdr:	common header structure
 * @pad_flags:	padding to remain compatible with old userspace code
 * @pad_binder:	padding to remain compatible with old userspace code
 * @fd:		file descriptor
 * @cookie:	opaque data, used by user-space
 */
struct rayo_binder_fd_object {
	struct rayo_binder_object_header	hdr;
	__u32				pad_flags;
	union {
		rayo_binder_uintptr_t	pad_binder;
		__u32			fd;
	};

	rayo_binder_uintptr_t		cookie;
};

/* struct rayo_binder_buffer_object - object describing a userspace buffer
 * @hdr:		common header structure
 * @flags:		one or more BINDER_BUFFER_* flags
 * @buffer:		address of the buffer
 * @length:		length of the buffer
 * @parent:		index in offset array pointing to parent buffer
 * @parent_offset:	offset in @parent pointing to this buffer
 *
 * A rayo_binder_buffer object represents an object that the
 * binder kernel driver can copy verbatim to the target
 * address space. A buffer itself may be pointed to from
 * within another buffer, meaning that the pointer inside
 * that other buffer needs to be fixed up as well. This
 * can be done by setting the BINDER_BUFFER_FLAG_HAS_PARENT
 * flag in @flags, by setting @parent buffer to the index
 * in the offset array pointing to the parent rayo_binder_buffer_object,
 * and by setting @parent_offset to the offset in the parent buffer
 * at which the pointer to this buffer is located.
 */
struct rayo_binder_buffer_object {
	struct rayo_binder_object_header	hdr;
	__u32				flags;
	rayo_binder_uintptr_t		buffer;
	rayo_binder_size_t			length;
	rayo_binder_size_t			parent;
	rayo_binder_size_t			parent_offset;
};

enum {
	BINDER_BUFFER_FLAG_HAS_PARENT = 0x01,
};

/* struct rayo_binder_fd_array_object - object describing an array of fds in a buffer
 * @hdr:		common header structure
 * @pad:		padding to ensure correct alignment
 * @num_fds:		number of file descriptors in the buffer
 * @parent:		index in offset array to buffer holding the fd array
 * @parent_offset:	start offset of fd array in the buffer
 *
 * A rayo_binder_fd_array object represents an array of file
 * descriptors embedded in a rayo_binder_buffer_object. It is
 * different from a regular rayo_binder_buffer_object because it
 * describes a list of file descriptors to fix up, not an opaque
 * blob of memory, and hence the kernel needs to treat it differently.
 *
 * An example of how this would be used is with Android's
 * native_handle_t object, which is a struct with a list of integers
 * and a list of file descriptors. The native_handle_t struct itself
 * will be represented by a struct rayo_binder_buffer_objct, whereas the
 * embedded list of file descriptors is represented by a
 * struct rayo_binder_fd_array_object with that rayo_binder_buffer_object as
 * a parent.
 */
struct rayo_binder_fd_array_object {
	struct rayo_binder_object_header	hdr;
	__u32				pad;
	rayo_binder_size_t			num_fds;
	rayo_binder_size_t			parent;
	rayo_binder_size_t			parent_offset;
};

/*
 * On 64-bit platforms where user code may run in 32-bits the driver must
 * translate the buffer (and local binder) addresses appropriately.
 */

struct rayo_binder_write_read {
	rayo_binder_size_t		write_size;	/* bytes to write */
	rayo_binder_size_t		write_consumed;	/* bytes consumed by driver */
	rayo_binder_uintptr_t	write_buffer;
	rayo_binder_size_t		read_size;	/* bytes to read */
	rayo_binder_size_t		read_consumed;	/* bytes consumed by driver */
	rayo_binder_uintptr_t	read_buffer;
};

/* Use with BINDER_VERSION, driver fills in fields. */
struct rayo_binder_version {
	/* driver protocol version -- increment with incompatible change */
	__s32       protocol_version;
};

/* This is the current protocol version. */
#ifdef BINDER_IPC_32BIT
#define BINDER_CURRENT_PROTOCOL_VERSION 7
#else
#define BINDER_CURRENT_PROTOCOL_VERSION 8
#endif

#define BINDER_WRITE_READ		_IOWR('b', 1, struct rayo_binder_write_read)
#define	BINDER_SET_IDLE_TIMEOUT		_IOW('b', 3, __s64)
#define	BINDER_SET_MAX_THREADS		_IOW('b', 5, __u32)
#define	BINDER_SET_IDLE_PRIORITY	_IOW('b', 6, __s32)
#define	BINDER_SET_CONTEXT_MGR		_IOW('b', 7, __s32)
#define	BINDER_THREAD_EXIT		_IOW('b', 8, __s32)
#define BINDER_VERSION			_IOWR('b', 9, struct rayo_binder_version)

/*
 * NOTE: Two special error codes you should check for when calling
 * in to the driver are:
 *
 * EINTR -- The operation has been interupted.  This should be
 * handled by retrying the ioctl() until a different error code
 * is returned.
 *
 * ECONNREFUSED -- The driver is no longer accepting operations
 * from your process.  That is, the process is being destroyed.
 * You should handle this by exiting from your process.  Note
 * that once this error code is returned, all further calls to
 * the driver from any thread will return this same code.
 */

enum transaction_flags {
	TF_ONE_WAY	= 0x01,	/* this is a one-way call: async, no return */
	TF_ROOT_OBJECT	= 0x04,	/* contents are the component's root object */
	TF_STATUS_CODE	= 0x08,	/* contents are a 32-bit status code */
	TF_ACCEPT_FDS	= 0x10,	/* allow replies with file descriptors */
};

struct rayo_binder_transaction_data {
	/* The first two are only used for bcTRANSACTION and brTRANSACTION,
	 * identifying the target and contents of the transaction.
	 */
	union {
		__u32	handle;	/* target descriptor of command transaction */
		rayo_binder_uintptr_t ptr;	/* target descriptor of return transaction */
	} target;
	rayo_binder_uintptr_t	cookie;	/* target object cookie */
	__u32		code;		/* transaction command */

	/* General information about the transaction. */
	__u32	        flags;
	pid_t		sender_pid;
	uid_t		sender_euid;
	rayo_binder_size_t	data_size;	/* number of bytes of data */
	rayo_binder_size_t	offsets_size;	/* number of bytes of offsets */

	/* If this transaction is inline, the data immediately
	 * follows here; otherwise, it ends with a pointer to
	 * the data buffer.
	 */
	union {
		struct {
			/* transaction data */
			rayo_binder_uintptr_t	buffer;
			/* offsets from buffer to flat_rayo_binder_object structs */
			rayo_binder_uintptr_t	offsets;
		} ptr;
		__u8	buf[8];
	} data;
};

struct rayo_binder_transaction_data_sg {
	struct rayo_binder_transaction_data transaction_data;
	rayo_binder_size_t buffers_size;
};

struct rayo_binder_ptr_cookie {
	rayo_binder_uintptr_t ptr;
	rayo_binder_uintptr_t cookie;
};

struct rayo_binder_handle_cookie {
	__u32 handle;
	rayo_binder_uintptr_t cookie;
} __attribute__((packed));

struct rayo_binder_pri_desc {
	__s32 priority;
	__u32 desc;
};

struct rayo_binder_pri_ptr_cookie {
	__s32 priority;
	rayo_binder_uintptr_t ptr;
	rayo_binder_uintptr_t cookie;
};

enum rayo_binder_driver_return_protocol {
	BR_ERROR = _IOR('r', 0, __s32),
	/*
	 * int: error code
	 */

	BR_OK = _IO('r', 1),
	/* No parameters! */

	BR_TRANSACTION = _IOR('r', 2, struct rayo_binder_transaction_data),
	BR_REPLY = _IOR('r', 3, struct rayo_binder_transaction_data),
	/*
	 * rayo_binder_transaction_data: the received command.
	 */

	BR_ACQUIRE_RESULT = _IOR('r', 4, __s32),
	/*
	 * not currently supported
	 * int: 0 if the last bcATTEMPT_ACQUIRE was not successful.
	 * Else the remote object has acquired a primary reference.
	 */

	BR_DEAD_REPLY = _IO('r', 5),
	/*
	 * The target of the last transaction (either a bcTRANSACTION or
	 * a bcATTEMPT_ACQUIRE) is no longer with us.  No parameters.
	 */

	BR_TRANSACTION_COMPLETE = _IO('r', 6),
	/*
	 * No parameters... always refers to the last transaction requested
	 * (including replies).  Note that this will be sent even for
	 * asynchronous transactions.
	 */

	BR_INCREFS = _IOR('r', 7, struct rayo_binder_ptr_cookie),
	BR_ACQUIRE = _IOR('r', 8, struct rayo_binder_ptr_cookie),
	BR_RELEASE = _IOR('r', 9, struct rayo_binder_ptr_cookie),
	BR_DECREFS = _IOR('r', 10, struct rayo_binder_ptr_cookie),
	/*
	 * void *:	ptr to binder
	 * void *: cookie for binder
	 */

	BR_ATTEMPT_ACQUIRE = _IOR('r', 11, struct rayo_binder_pri_ptr_cookie),
	/*
	 * not currently supported
	 * int:	priority
	 * void *: ptr to binder
	 * void *: cookie for binder
	 */

	BR_NOOP = _IO('r', 12),
	/*
	 * No parameters.  Do nothing and examine the next command.  It exists
	 * primarily so that we can replace it with a BR_SPAWN_LOOPER command.
	 */

	BR_SPAWN_LOOPER = _IO('r', 13),
	/*
	 * No parameters.  The driver has determined that a process has no
	 * threads waiting to service incoming transactions.  When a process
	 * receives this command, it must spawn a new service thread and
	 * register it via bcENTER_LOOPER.
	 */

	BR_FINISHED = _IO('r', 14),
	/*
	 * not currently supported
	 * stop threadpool thread
	 */

	BR_DEAD_BINDER = _IOR('r', 15, rayo_binder_uintptr_t),
	/*
	 * void *: cookie
	 */
	BR_CLEAR_DEATH_NOTIFICATION_DONE = _IOR('r', 16, rayo_binder_uintptr_t),
	/*
	 * void *: cookie
	 */

	BR_FAILED_REPLY = _IO('r', 17),
	/*
	 * The the last transaction (either a bcTRANSACTION or
	 * a bcATTEMPT_ACQUIRE) failed (e.g. out of memory).  No parameters.
	 */
};

enum rayo_binder_driver_command_protocol {
	BC_TRANSACTION = _IOW('c', 0, struct rayo_binder_transaction_data),
	BC_REPLY = _IOW('c', 1, struct rayo_binder_transaction_data),
	/*
	 * rayo_binder_transaction_data: the sent command.
	 */

	BC_ACQUIRE_RESULT = _IOW('c', 2, __s32),
	/*
	 * not currently supported
	 * int:  0 if the last BR_ATTEMPT_ACQUIRE was not successful.
	 * Else you have acquired a primary reference on the object.
	 */

	BC_FREE_BUFFER = _IOW('c', 3, rayo_binder_uintptr_t),
	/*
	 * void *: ptr to transaction data received on a read
	 */

	BC_INCREFS = _IOW('c', 4, __u32),
	BC_ACQUIRE = _IOW('c', 5, __u32),
	BC_RELEASE = _IOW('c', 6, __u32),
	BC_DECREFS = _IOW('c', 7, __u32),
	/*
	 * int:	descriptor
	 */

	BC_INCREFS_DONE = _IOW('c', 8, struct rayo_binder_ptr_cookie),
	BC_ACQUIRE_DONE = _IOW('c', 9, struct rayo_binder_ptr_cookie),
	/*
	 * void *: ptr to binder
	 * void *: cookie for binder
	 */

	BC_ATTEMPT_ACQUIRE = _IOW('c', 10, struct rayo_binder_pri_desc),
	/*
	 * not currently supported
	 * int: priority
	 * int: descriptor
	 */

	BC_REGISTER_LOOPER = _IO('c', 11),
	/*
	 * No parameters.
	 * Register a spawned looper thread with the device.
	 */

	BC_ENTER_LOOPER = _IO('c', 12),
	BC_EXIT_LOOPER = _IO('c', 13),
	/*
	 * No parameters.
	 * These two commands are sent as an application-level thread
	 * enters and exits the binder loop, respectively.  They are
	 * used so the binder can have an accurate count of the number
	 * of looping threads it has available.
	 */

	BC_REQUEST_DEATH_NOTIFICATION = _IOW('c', 14, struct rayo_binder_handle_cookie),
	/*
	 * int: handle
	 * void *: cookie
	 */

	BC_CLEAR_DEATH_NOTIFICATION = _IOW('c', 15, struct rayo_binder_handle_cookie),
	/*
	 * int: handle
	 * void *: cookie
	 */

	BC_DEAD_BINDER_DONE = _IOW('c', 16, rayo_binder_uintptr_t),
	/*
	 * void *: cookie
	 */

	BC_TRANSACTION_SG = _IOW('c', 17, struct rayo_binder_transaction_data_sg),
	BC_REPLY_SG = _IOW('c', 18, struct rayo_binder_transaction_data_sg),
	/*
	 * rayo_binder_transaction_data_sg: the sent command.
	 */
};

#endif /* _UAPI_LINUX_BINDER_H */

