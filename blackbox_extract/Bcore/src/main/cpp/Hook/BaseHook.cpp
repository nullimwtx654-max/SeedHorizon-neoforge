#include "BaseHook.h"
#include "JniHook/JniHook.h"
#include <cstring>
#include <cstdlib>

struct SpoofedSysProp {
    const char *key;
    const char *value;
};

static const SpoofedSysProp kSpoofedProps[] = {
    {"ro.build.type", "user"},
    {"ro.build.tags", "release-keys"},
    {"ro.kernel.qemu", "0"},
    {"ro.kernel.android.qemud", ""},
    {"ro.boot.qemu", "0"},
    {"ro.debuggable", "0"},
    {"ro.secure", "1"},
    {"ro.boot.verifiedbootstate", "green"},
    {"ro.boot.flash.locked", "1"},
    {"ro.boot.vbmeta.device_state", "locked"},
    {"ro.boot.veritymode", "enforcing"},
    {"ro.boot.warranty_bit", "0"},
    {"ro.warranty_bit", "0"},
    {"sys.usb.state", "mtp"},
    {"sys.usb.config", "mtp"},
    {"persist.sys.usb.config", "mtp"},
    {"init.svc.adbd", "stopped"},
    {"service.adb.root", "0"},
    {nullptr, nullptr}
};

static const char *lookupSpoofedProp(JNIEnv *env, jstring key) {
    if (!key) return nullptr;
    const char *ckey = env->GetStringUTFChars(key, JNI_FALSE);
    if (!ckey) return nullptr;
    const char *matched = nullptr;
    for (int i = 0; kSpoofedProps[i].key != nullptr; ++i) {
        if (strcmp(ckey, kSpoofedProps[i].key) == 0) {
            matched = kSpoofedProps[i].value;
            break;
        }
    }
    env->ReleaseStringUTFChars(key, ckey);
    return matched;
}

HOOK_JNI(jstring, native_get_1, JNIEnv *env, jclass clazz, jstring key) {
    const char *spoofed = lookupSpoofedProp(env, key);
    if (spoofed != nullptr) {
        return env->NewStringUTF(spoofed);
    }
    return orig_native_get_1(env, clazz, key);
}

HOOK_JNI(jstring, native_get_2, JNIEnv *env, jclass clazz, jstring key, jstring def) {
    const char *spoofed = lookupSpoofedProp(env, key);
    if (spoofed != nullptr) {
        return env->NewStringUTF(spoofed);
    }
    return orig_native_get_2(env, clazz, key, def);
}

HOOK_JNI(jint, native_get_int, JNIEnv *env, jclass clazz, jstring key, jint def) {
    const char *spoofed = lookupSpoofedProp(env, key);
    if (spoofed != nullptr && spoofed[0] != '\0') {
        return (jint) strtol(spoofed, nullptr, 10);
    }
    return orig_native_get_int(env, clazz, key, def);
}

HOOK_JNI(jlong, native_get_long, JNIEnv *env, jclass clazz, jstring key, jlong def) {
    const char *spoofed = lookupSpoofedProp(env, key);
    if (spoofed != nullptr && spoofed[0] != '\0') {
        return (jlong) strtoll(spoofed, nullptr, 10);
    }
    return orig_native_get_long(env, clazz, key, def);
}

HOOK_JNI(jboolean, native_get_boolean, JNIEnv *env, jclass clazz, jstring key, jboolean def) {
    const char *spoofed = lookupSpoofedProp(env, key);
    if (spoofed != nullptr) {
        if (strcmp(spoofed, "1") == 0 || strcmp(spoofed, "true") == 0) {
            return JNI_TRUE;
        }
        if (strcmp(spoofed, "0") == 0 || strcmp(spoofed, "false") == 0 || spoofed[0] == '\0') {
            return JNI_FALSE;
        }
    }
    return orig_native_get_boolean(env, clazz, key, def);
}

void BaseHook::init(JNIEnv *env) {
    const char *sysPropClass = "android/os/SystemProperties";
    JniHook::HookJniFun(env, sysPropClass, "native_get",
                        "(Ljava/lang/String;)Ljava/lang/String;",
                        (void *) new_native_get_1, (void **) (&orig_native_get_1), true);
    JniHook::HookJniFun(env, sysPropClass, "native_get",
                        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                        (void *) new_native_get_2, (void **) (&orig_native_get_2), true);
    JniHook::HookJniFun(env, sysPropClass, "native_get_int",
                        "(Ljava/lang/String;I)I",
                        (void *) new_native_get_int, (void **) (&orig_native_get_int), true);
    JniHook::HookJniFun(env, sysPropClass, "native_get_long",
                        "(Ljava/lang/String;J)J",
                        (void *) new_native_get_long, (void **) (&orig_native_get_long), true);
    JniHook::HookJniFun(env, sysPropClass, "native_get_boolean",
                        "(Ljava/lang/String;Z)Z",
                        (void *) new_native_get_boolean, (void **) (&orig_native_get_boolean), true);
}
