#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

chmod +x ./gradlew
if [ -f "./gms-core/gradlew" ]; then
  chmod +x ./gms-core/gradlew
fi

mkdir -p "$ROOT_DIR/Bcore/src/main/assets/gms"

if [ -d "$ROOT_DIR/gms-core" ] && [ "${BUILD_GMS_FROM_SOURCE:-0}" = "1" ]; then
  echo "==> Building microG GmsCore and FakeStore from source..."
  (
    cd "$ROOT_DIR/gms-core"
    export GRADLE_MICROG_VERSION_WITHOUT_GIT=1
    ./gradlew :play-services-core:assembleMapboxDefaultRelease :vending-app:assembleDefaultRelease --no-daemon
  ) || true
  GMS_APK=$(find "$ROOT_DIR/gms-core/play-services-core/build/outputs/apk" -name "com.google.android.gms-*.apk" ! -name "*unsigned*" 2>/dev/null | head -n 1 || true)
  VENDING_APK=$(find "$ROOT_DIR/gms-core/vending-app/build/outputs/apk" -name "com.android.vending-*.apk" ! -name "*unsigned*" 2>/dev/null | head -n 1 || true)
  if [ -n "$GMS_APK" ] && [ -f "$GMS_APK" ]; then
    cp -f "$GMS_APK" "$ROOT_DIR/Bcore/src/main/assets/gms/GmsCore.apk"
  fi
  if [ -n "$VENDING_APK" ] && [ -f "$VENDING_APK" ]; then
    cp -f "$VENDING_APK" "$ROOT_DIR/Bcore/src/main/assets/gms/FakeStore.apk"
  fi
fi

echo "==> Building NewBlackbox Debug & Release APKs (ensureMicroGAssets will fetch microG if needed)..."
./gradlew assembleDebug assembleRelease :Bcore:assembleDebug :Bcore:assembleRelease :black-reflection:assemble :compiler:assemble --no-daemon

echo "==> Build complete! Output APKs:"
ls -lh "$ROOT_DIR/app/build/outputs/apk/debug/"*.apk "$ROOT_DIR/app/build/outputs/apk/release/"*.apk
