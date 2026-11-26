#include "FileSystemHook.h"
#include "Log.h"

extern void ensure_antidetection_file_hooks();

void FileSystemHook::init() {
    ALOGD("FileSystemHook: Ensuring native file system hooks are active");
    ensure_antidetection_file_hooks();
}
