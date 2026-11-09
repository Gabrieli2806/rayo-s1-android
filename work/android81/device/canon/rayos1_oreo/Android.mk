LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := libtg
LOCAL_VENDOR_MODULE := true
LOCAL_SRC_FILES := bt_timer_compat.c
LOCAL_SHARED_LIBRARIES := libc libdl
LOCAL_LDFLAGS := -Wl,--no-as-needed
LOCAL_CFLAGS := -Wall -Wextra -Werror
include $(BUILD_SHARED_LIBRARY)
