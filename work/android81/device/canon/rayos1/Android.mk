LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := hwcomposer.rayo_adapter
LOCAL_MODULE_RELATIVE_PATH := hw
LOCAL_SRC_FILES := hwc_adapter.cpp
LOCAL_SHARED_LIBRARIES := libhardware liblog libdl
LOCAL_CPPFLAGS := -std=c++14 -DTARGET_BOARD_PLATFORM_RK312X
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := rayo_abi_probe
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := probe_abi.c
LOCAL_SHARED_LIBRARIES := libdl liblog
LOCAL_C_INCLUDES := hardware/libhardware/include
LOCAL_CFLAGS := -Wall -Wextra -Werror
include $(BUILD_EXECUTABLE)

include $(CLEAR_VARS)
LOCAL_MODULE := rayo_hwbinder_probe
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := probe_hwbinder.cpp
LOCAL_SHARED_LIBRARIES := libhwbinder libutils libcutils liblog
LOCAL_CFLAGS := -Wall -Wextra -Werror -DBINDER_IPC_32BIT
LOCAL_CPPFLAGS := -std=c++14
include $(BUILD_EXECUTABLE)

include $(CLEAR_VARS)
LOCAL_MODULE := rayo_gpu_probe
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := probe_gpu.c
LOCAL_SHARED_LIBRARIES := libdl
LOCAL_C_INCLUDES := frameworks/native/opengl/include
LOCAL_CFLAGS := -Wall -Wextra -Werror
include $(BUILD_EXECUTABLE)
