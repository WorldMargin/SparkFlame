LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := sparkfile
LOCAL_SRC_FILES := spark_file.c
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
