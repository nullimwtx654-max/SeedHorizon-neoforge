#include <android/log.h>
#include "../IO.h"

#define LOG_TAG "AntiDetection"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

/**
 * NOTE: Do NOT use DobbyHook on Bionic libc.so functions (access, stat, lstat, readlink,
 * faccessat, fstatat, fopen) because on Android 12-15 ARM64 devices:
 * 1. Bionic's access/stat/lstat/readlink are 12-byte wrapper stubs that branch directly into
 *    faccessat/fstatat/readlinkat, so inline trampolines overwrite adjacent functions.
 * 2. Modern Android maps libc.so with ARM64 BTI (Branch Target Identification) and XOM
 *    (Execute-Only Memory), causing prebuilt 2020 DobbyHook to fault with SIGILL/SIGSEGV.
 *
 * File redirection, root/Xposed/Magisk path hiding, and /proc/self/{maps,status,mounts,cmdline}
 * sanitization are handled via ART RegisterNatives (JniHook in UnixFileSystemHook, RuntimeHook,
 * VMClassLoaderHook, BaseHook) and Libcore.os dynamic proxy (OsStub + IOCore).
 */
void ensure_antidetection_file_hooks() {
    LOGD("AntiDetection: JNI and Libcore.os file hooks active (BTI/XOM safe)");
}
