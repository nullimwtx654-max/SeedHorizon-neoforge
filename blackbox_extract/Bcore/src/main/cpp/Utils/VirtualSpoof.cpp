#include <android/log.h>

#define LOG_TAG "VirtualSpoof"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

/**
 * System property spoofing is performed via JniHook (env->RegisterNatives on
 * android.os.SystemProperties in BaseHook.cpp) and VirtualRuntime.setupRuntime(),
 * avoiding inline DobbyHook patching on libc.so's __system_property_get which
 * triggers SIGILL/SIGSEGV on Android 12-15 ARM64 BTI/XOM devices.
 */
void install_property_get_hook() {
    LOGD("VirtualSpoof: Using JniHook on android.os.SystemProperties (BTI/XOM safe)");
}
