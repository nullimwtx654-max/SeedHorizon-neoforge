#!/usr/bin/env bash
#
# voxel-engine/build.sh
#
# Build script for the native C voxel engine.
#

set -e

# ─── Configuration ─────────────────────────────────────────────────
CC=${CC:-gcc}
OUT="voxel-engine"
SRC1="./voxel-engine/engine.c"
SRC2=""            # add any additional .c files here
CFLAGS="-O3 -Wall -Wextra -std=c99 -D_DEFAULT_SOURCE -MMD -MP"
DEFINES=""
INCLUDE_DIRS=""
LIBS=""
TARGET_PLATFORM="desktop"   # or "android"

# ─── Platform-specific options ─────────────────────────────────────
if [ "$TARGET_PLATFORM" = "android" ]; then
    # Android NDK: needs libEGL, libGLESv2, libGLESv3
    include_dirs="-I/data/user/0/com.androidharness.app/files/linux/usr/include/EGL "
    include_dirs="${include_dirs}-I/data/user/0/com.androidharness.app/files/linux/usr/include/GLESv2 "
    include_dirs="${include_dirs}-I/data/user/0/com.androidharness.app/files/linux/usr/include/GLESv3 "
    LIBS="-lEGL -lGLESv2 -lGLESv3 -lm"
    if command -v aarch64-linux-gnu-gcc &>/dev/null; then
        CC="aarch64-linux-gnu-gcc"
    fi
    echo "Building for ANDROID with EGL + GLES."
else
    # Desktop Linux: try EGL + GLES headers
    for inc in /data/user/0/com.androidharness.app/files/linux/usr/include/EGL/ \
               /data/user/0/com.androidharness.app/files/linux/usr/include/GLES-CM/ \
               /data/user/0/com.androidharness.app/files/linux/usr/include/GLESv3/ \
               /data/user/0/com.androidharness.app/files/linux/usr/include/GLESv2/ \
               /usr/include/EGL/ \
               /usr/include/GLESv3/; do
        if [ -f "$inc/egl.h" ] 2>/dev/null; then
            include_dirs="${include_dirs}-I$inc "
            break
        fi
    done

    if [ -z "$include_dirs" ]; then
        # Fallback: pure software (PPM output only)
        DEFINES="-DNO_EGL"
        echo "No EGL/GLES headers found. Building in NO_EGL software mode."
    else
        LIBS="-lEGL -lGLESv2 -lGLESv3 -lm"
    fi
    echo "Building for DESKTOP."
fi

# ─── Compile ────────────────────────────────────────────────────────
echo "=== Compiling ==="
echo "  CC = $CC"
echo "  CFLAGS = $CFLAGS $DEFINES"
echo "  Includes = $include_dirs"
echo "  Links = $LIBS"

if [ -f "$SRC1" ]; then
    export PATH="/data/user/0/com.androidharness.app/files/linux/bin:/system/bin:/system/xbin:/vendor/bin:$PATH"
    $CC $CFLAGS $DEFINES $INCLUDE_DIRS -o "$OUT" "$SRC1" -ldl ${INCLUDE_DIRS// / } ${LIBS// / }
    echo "=== Build successful ==="
    echo "  $PWD/$OUT"
else
    echo "=== ERROR: Source file not found: $SRC1 ==="
    exit 1
fi
