#!/usr/bin/env bash
# 构建 ZCode 安卓版壳 APK。前置：JDK 17+、Android SDK（platforms;android-36、build-tools）。
# 用法：
#   ./scripts/build-apk.sh              # debug 包（自动带 debug 签名，可直接装机）
#   ./scripts/build-apk.sh release      # release 包（需 ZCODE_KEYSTORE_PATH 等环境变量，否则产出未签名包）
set -euo pipefail

cd "$(dirname "$0")/.."

TASK="${1:-debug}"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

if [[ ! -d "$ANDROID_HOME/platforms" ]]; then
  echo "找不到 Android SDK：ANDROID_HOME=$ANDROID_HOME" >&2
  echo "请安装 command line tools 后执行：sdkmanager \"platform-tools\" \"platforms;android-36\" \"build-tools;36.1.0\"" >&2
  exit 1
fi

if [[ "$TASK" == "release" ]]; then
  ./gradlew --no-daemon :app:assembleRelease
  APK=$(find app/build/outputs/apk/release -name '*.apk' | head -1)
else
  ./gradlew --no-daemon :app:assembleDebug
  APK=$(find app/build/outputs/apk/debug -name '*.apk' | head -1)
fi

echo
echo "APK: $APK"
[[ -n "$APK" ]] && du -h "$APK"
echo "安装：adb install -r \"$APK\""
