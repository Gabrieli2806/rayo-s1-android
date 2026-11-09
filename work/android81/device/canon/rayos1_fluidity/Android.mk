LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := hwcomposer.rayo_fluidity
LOCAL_MODULE_RELATIVE_PATH := hw
LOCAL_SRC_FILES := hwc_adapter.cpp
LOCAL_SHARED_LIBRARIES := libhardware liblog libdl
LOCAL_CPPFLAGS := -std=c++14 -DTARGET_BOARD_PLATFORM_RK312X
include $(BUILD_SHARED_LIBRARY)
