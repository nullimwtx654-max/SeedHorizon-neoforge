#include "IO.h"
#include "Log.h"
#include <mutex>
#include <limits.h>

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

jmethodID getAbsolutePathMethodId = nullptr;

static list<IO::RelocateInfo> relocate_rule;
static std::mutex relocate_mutex;
static volatile bool sIoEnabled = false;

static thread_local char sTlsBuffers[4][PATH_MAX];
static thread_local int sTlsIdx = 0;

static const char *sBlockedExactOrPrefix[] = {
    "/system/bin/su",
    "/system/xbin/su",
    "/sbin/su",
    "/su/bin/su",
    "/data/local/xbin/su",
    "/data/local/bin/su",
    "/data/local/tmp/su",
    "/data/local/su",
    "/system/sd/xbin/su",
    "/system/bin/failsafe/su",
    "/system/bin/.ext/.su",
    "/system/usr/we-need-root/su-backup",
    "/system/xbin/mu",
    "/system/xbin/daemonsu",
    "/system/xbin/sugote",
    "/system/bin/sugote-mksh",
    "/system/xbin/sugote-mksh",
    "/system/app/Superuser.apk",
    "/system/app/SuperSU.apk",
    "/system/etc/init.d/99SuperSUDaemon",
    "/system/bin/magisk",
    "/system/xbin/magisk",
    "/sbin/magisk",
    "/data/adb/magisk",
    "/data/adb/ksu",
    "/data/adb/ap",
    "/data/adb/modules",
    "/dev/socket/magisk",
    "/system/xposed.prop",
    "/system/framework/XposedBridge.jar",
    "/dev/vboxguest",
    "/dev/vboxuser",
    "/dev/qemu_pipe",
    "/dev/goldfish_pipe",
    "/dev/socket/qemud",
    "/dev/socket/baseband_genyd",
    "/dev/socket/genyd",
    "/sys/qemu_trace",
    "/system/bin/qemu-props",
    "/system/bin/nox-prop",
    "/system/lib/libc_malloc_debug_qemu.so",
    "/sys/module/goldfish_audio",
    "/sys/module/goldfish_sync",
    "/proc/tty/drivers/goldfish",
    "/dev/goldfish_events",
    "/system/lib/libdroid4x.so",
    "/system/bin/windroyed",
    "/system/lib/libnoxspeedup.so",
    "/system/lib/libmemu.so",
    "/system/lib/libbluelog.so",
    "/data/data/de.robv.android.xposed.installer",
    "/data/data/org.meowcat.edxposed.manager",
    "/data/data/org.lsposed.manager",
    "/data/data/top.canyie.dreamland.manager",
    "/data/data/com.topjohnwu.magisk",
    "/data/data/eu.chainfire.supersu",
    "/data/data/com.noshufou.android.su",
    "/data/data/com.koushikdutta.superuser",
    "/data/data/io.va.exposed",
    "/data/data/com.lody.virtual",
    "/data/data/com.excelliance.dualaid",
    "/data/data/com.lbe.parallel",
    "/data/data/com.dual.dualspace",
    "/data/data/com.ludashi.superboost",
    "/data/data/top.niunaijun.blackboxa",
    nullptr
};

bool IO::isEnabled() {
    return sIoEnabled;
}

bool IO::isBlockedPath(const char *path) {
    if (!path || path[0] != '/') {
        return false;
    }
    if (strstr(path, "/blackbox/") != nullptr ||
        strstr(path, "/proc/net/") != nullptr ||
        strstr(path, "/dev/socket/logd") != nullptr) {
        return false;
    }
    for (int i = 0; sBlockedExactOrPrefix[i] != nullptr; ++i) {
        const char *b = sBlockedExactOrPrefix[i];
        size_t blen = strlen(b);
        if (strncmp(path, b, blen) == 0 && (path[blen] == '\0' || path[blen] == '/')) {
            return true;
        }
    }
    return false;
}

const char *IO::redirectPath(const char *__path) {
    if (!__path || __path[0] == '\0') {
        return __path;
    }
    if (strstr(__path, "/blackbox/") != nullptr) {
        return __path;
    }

    std::lock_guard<std::mutex> lock(relocate_mutex);
    for (const auto &info : relocate_rule) {
        if (!info.targetPath || !info.relocatePath) {
            continue;
        }
        size_t tlen = strlen(info.targetPath);
        if (tlen > 0 && strncmp(__path, info.targetPath, tlen) == 0 &&
            (__path[tlen] == '\0' || __path[tlen] == '/')) {
            char *buf = sTlsBuffers[sTlsIdx];
            sTlsIdx = (sTlsIdx + 1) & 3;
            snprintf(buf, PATH_MAX, "%s%s", info.relocatePath, __path + tlen);
            return buf;
        }
    }
    return __path;
}

jstring IO::redirectPath(JNIEnv *env, jstring path) {
    if (!path) {
        return nullptr;
    }
    return BoxCore::redirectPathString(env, path);
}

jobject IO::redirectPath(JNIEnv *env, jobject path) {
    if (!path) {
        return nullptr;
    }
    return BoxCore::redirectPathFile(env, path);
}

void IO::addRule(const char *targetPath, const char *relocatePath) {
    if (!targetPath || !relocatePath) {
        return;
    }
    std::lock_guard<std::mutex> lock(relocate_mutex);
    IO::RelocateInfo info{};
    info.targetPath = strdup(targetPath);
    info.relocatePath = strdup(relocatePath);
    relocate_rule.push_back(info);
}

void IO::init(JNIEnv *env) {
    jclass tmpFile = env->FindClass("java/io/File");
    if (tmpFile) {
        getAbsolutePathMethodId = env->GetMethodID(tmpFile, "getAbsolutePath", "()Ljava/lang/String;");
    }
    sIoEnabled = true;
}
