#include <IO.h>
#include "UnixFileSystemHook.h"
#include "JniHook/JniHook.h"
#include "BaseHook.h"
#include <vector>
#include <string>

static jstring getFileAbsolutePath(JNIEnv *env, jobject file) {
    if (!file) return nullptr;
    jclass fileClass = env->GetObjectClass(file);
    if (!fileClass) return nullptr;
    jmethodID mid = env->GetMethodID(fileClass, "getAbsolutePath", "()Ljava/lang/String;");
    if (!mid) {
        env->ExceptionClear();
        return nullptr;
    }
    auto res = (jstring) env->CallObjectMethod(file, mid);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    return res;
}

static bool isBlockedJavaString(JNIEnv *env, jstring pathStr) {
    if (!pathStr) return false;
    const char *cpath = env->GetStringUTFChars(pathStr, JNI_FALSE);
    if (!cpath) return false;
    bool blocked = IO::isBlockedPath(cpath);
    env->ReleaseStringUTFChars(pathStr, cpath);
    return blocked;
}

static bool isBlockedJavaFile(JNIEnv *env, jobject file) {
    jstring absPath = getFileAbsolutePath(env, file);
    if (!absPath) return false;
    bool blocked = isBlockedJavaString(env, absPath);
    env->DeleteLocalRef(absPath);
    return blocked;
}

HOOK_JNI(jstring, canonicalize0, JNIEnv *env, jobject obj, jstring path) {
    jstring redirect = IO::redirectPath(env, path);
    return orig_canonicalize0(env, obj, redirect);
}

HOOK_JNI(jint, getBooleanAttributes0, JNIEnv *env, jobject obj, jstring abspath) {
    if (isBlockedJavaString(env, abspath)) {
        return 0;
    }
    jstring redirect = IO::redirectPath(env, abspath);
    return orig_getBooleanAttributes0(env, obj, redirect);
}

HOOK_JNI(jint, getBooleanAttributes0_File, JNIEnv *env, jobject obj, jobject file) {
    if (isBlockedJavaFile(env, file)) {
        return 0;
    }
    jobject redirect = IO::redirectPath(env, file);
    return orig_getBooleanAttributes0_File(env, obj, redirect);
}

HOOK_JNI(jlong, getLastModifiedTime0, JNIEnv *env, jobject obj, jobject path) {
    if (isBlockedJavaFile(env, path)) {
        return 0;
    }
    jobject redirect = IO::redirectPath(env, path);
    return orig_getLastModifiedTime0(env, obj, redirect);
}

HOOK_JNI(jboolean, setPermission0, JNIEnv *env, jobject obj, jobject file, jint access,
         jboolean enable, jboolean owneronly) {
    jobject redirect = IO::redirectPath(env, file);
    return orig_setPermission0(env, obj, redirect, access, enable, owneronly);
}

HOOK_JNI(jboolean, createFileExclusively0, JNIEnv *env, jobject obj, jstring path) {
    jstring redirect = IO::redirectPath(env, path);
    return orig_createFileExclusively0(env, obj, redirect);
}

HOOK_JNI(jobjectArray, list0, JNIEnv *env, jobject obj, jobject file) {
    if (isBlockedJavaFile(env, file)) {
        return nullptr;
    }
    jobject redirect = IO::redirectPath(env, file);
    jobjectArray arr = orig_list0(env, obj, redirect);
    if (!arr) return nullptr;

    jsize len = env->GetArrayLength(arr);
    if (len <= 0) return arr;

    std::vector<jstring> kept;
    kept.reserve(len);
    bool filteredAny = false;

    for (jsize i = 0; i < len; ++i) {
        auto item = (jstring) env->GetObjectArrayElement(arr, i);
        if (!item) continue;
        const char *cstr = env->GetStringUTFChars(item, JNI_FALSE);
        bool drop = false;
        if (cstr) {
            if (strcmp(cstr, "su") == 0 ||
                strcmp(cstr, "magisk") == 0 ||
                strcmp(cstr, "daemonsu") == 0 ||
                strcmp(cstr, "sugote") == 0 ||
                strcmp(cstr, "Superuser.apk") == 0 ||
                strcmp(cstr, "SuperSU.apk") == 0 ||
                strcmp(cstr, "XposedBridge.jar") == 0 ||
                strcmp(cstr, "xposed.prop") == 0) {
                drop = true;
            }
            env->ReleaseStringUTFChars(item, cstr);
        }
        if (drop) {
            filteredAny = true;
            env->DeleteLocalRef(item);
        } else {
            kept.push_back(item);
        }
    }

    if (!filteredAny) {
        for (jstring s : kept) {
            env->DeleteLocalRef(s);
        }
        return arr;
    }

    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray newArr = env->NewObjectArray((jsize) kept.size(), stringClass, nullptr);
    for (size_t i = 0; i < kept.size(); ++i) {
        env->SetObjectArrayElement(newArr, (jsize) i, kept[i]);
        env->DeleteLocalRef(kept[i]);
    }
    return newArr;
}

HOOK_JNI(jboolean, createDirectory0, JNIEnv *env, jobject obj, jobject path) {
    jobject redirect = IO::redirectPath(env, path);
    return orig_createDirectory0(env, obj, redirect);
}

HOOK_JNI(jboolean, setLastModifiedTime0, JNIEnv *env, jobject obj, jobject file, jlong time) {
    jobject redirect = IO::redirectPath(env, file);
    return orig_setLastModifiedTime0(env, obj, redirect, time);
}

HOOK_JNI(jboolean, setReadOnly0, JNIEnv *env, jobject obj, jobject file) {
    jobject redirect = IO::redirectPath(env, file);
    return orig_setReadOnly0(env, obj, redirect);
}

HOOK_JNI(jlong, getSpace0, JNIEnv *env, jobject obj, jobject file, jint t) {
    jobject redirect = IO::redirectPath(env, file);
    return orig_getSpace0(env, obj, redirect, t);
}

HOOK_JNI(jboolean, delete0, JNIEnv *env, jobject obj, jobject file) {
    jobject redirect = IO::redirectPath(env, file);
    return orig_delete0(env, obj, redirect);
}

HOOK_JNI(jlong, getLength0, JNIEnv *env, jobject obj, jobject file) {
    if (isBlockedJavaFile(env, file)) {
        return 0;
    }
    jobject redirect = IO::redirectPath(env, file);
    return orig_getLength0(env, obj, redirect);
}

void UnixFileSystemHook::init(JNIEnv *env) {
    const char *className = "java/io/UnixFileSystem";

    JniHook::HookJniFun(env, className, "canonicalize0", "(Ljava/lang/String;)Ljava/lang/String;",
                        (void *) new_canonicalize0, (void **) (&orig_canonicalize0), false);

    // Android 11+ signature: (Ljava/lang/String;)I
    JniHook::HookJniFun(env, className, "getBooleanAttributes0", "(Ljava/lang/String;)I",
                        (void *) new_getBooleanAttributes0, (void **) (&orig_getBooleanAttributes0), false);

    // Android 7-10 signature: (Ljava/io/File;)I
    JniHook::HookJniFun(env, className, "getBooleanAttributes0", "(Ljava/io/File;)I",
                        (void *) new_getBooleanAttributes0_File, (void **) (&orig_getBooleanAttributes0_File), false);

    JniHook::HookJniFun(env, className, "getLastModifiedTime0", "(Ljava/io/File;)J",
                        (void *) new_getLastModifiedTime0, (void **) (&orig_getLastModifiedTime0), false);

    JniHook::HookJniFun(env, className, "setPermission0", "(Ljava/io/File;IZZ)Z",
                        (void *) new_setPermission0, (void **) (&orig_setPermission0), false);

    JniHook::HookJniFun(env, className, "createFileExclusively0", "(Ljava/lang/String;)Z",
                        (void *) new_createFileExclusively0, (void **) (&orig_createFileExclusively0), false);

    JniHook::HookJniFun(env, className, "list0", "(Ljava/io/File;)[Ljava/lang/String;",
                        (void *) new_list0, (void **) (&orig_list0), false);

    JniHook::HookJniFun(env, className, "createDirectory0", "(Ljava/io/File;)Z",
                        (void *) new_createDirectory0, (void **) (&orig_createDirectory0), false);

    JniHook::HookJniFun(env, className, "setLastModifiedTime0", "(Ljava/io/File;J)Z",
                        (void *) new_setLastModifiedTime0, (void **) (&orig_setLastModifiedTime0), false);

    JniHook::HookJniFun(env, className, "setReadOnly0", "(Ljava/io/File;)Z",
                        (void *) new_setReadOnly0, (void **) (&orig_setReadOnly0), false);

    JniHook::HookJniFun(env, className, "getSpace0", "(Ljava/io/File;I)J",
                        (void *) new_getSpace0, (void **) (&orig_getSpace0), false);

    JniHook::HookJniFun(env, className, "delete0", "(Ljava/io/File;)Z",
                        (void *) new_delete0, (void **) (&orig_delete0), false);

    JniHook::HookJniFun(env, className, "getLength0", "(Ljava/io/File;)J",
                        (void *) new_getLength0, (void **) (&orig_getLength0), false);
}