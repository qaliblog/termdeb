LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := libtermux-bootstrap
LOCAL_SRC_FILES := termux-bootstrap-zip.S termux-bootstrap.c
include $(BUILD_SHARED_LIBRARY)

# libadbpty: PTY helper for the TermDeb ADB bridge (com.termux.app.adb.AdbPty).
include $(CLEAR_VARS)
LOCAL_MODULE := libadbpty
LOCAL_SRC_FILES := adb/adb_pty_jni.c
LOCAL_CFLAGS := -std=c11 -Wall -Wextra -Os -fno-stack-protector
include $(BUILD_SHARED_LIBRARY)
