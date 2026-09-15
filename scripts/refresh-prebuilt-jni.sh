#!/usr/bin/env bash
#
# SPDX-License-Identifier: GPL-3.0-or-later
#
# 把最新的原生构建产物 strip 后刷新到预编译库。
#
# 为什么要这个脚本:
#   本仓库默认走"预编译 jni"模式 —— 构建时把 third_party/android-native-libs/
#   里的 libreverie_jni.so (以及全部 Krita/Qt 动态库) 拷进 src/main/jniLibs 打包。
#   如果这份预编译库落后于 C++ 源码 (例如新增了 JNI 方法却没刷新它),
#   打包出来的 APK 一调用新方法就 UnsatisfiedLinkError 崩溃
#   (例如"创建动画项目崩溃: animationEnabled 无实现")。
#   注意 src/main/jniLibs 里的副本会顶掉 CMake 产物, 所以它也必须一起刷新。
#
# 用法: ./scripts/refresh-prebuilt-jni.sh
#   (先跑一次 ./gradlew assembleDebug -PbuildNative -PcmakeArgs="-DQT_ANDROID_DIR=..." 生成产物)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/25.2.9519653}"
STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"

if [ ! -x "$STRIP" ]; then
    echo "找不到 llvm-strip: $STRIP (设置 ANDROID_NDK_HOME 指向 NDK 25.2.9519653)" >&2
    exit 1
fi

SO="$(ls -t "$ROOT"/app/build/intermediates/cxx/Release/*/obj/arm64-v8a/libreverie_jni.so 2>/dev/null | head -1 || true)"
if [ -z "$SO" ]; then
    SO="$(ls -t "$ROOT"/app/build/intermediates/cmake/*/obj/arm64-v8a/libreverie_jni.so 2>/dev/null | head -1 || true)"
fi
if [ -z "$SO" ]; then
    echo "找不到 CMake 产物; 先执行: ./gradlew assembleDebug -PbuildNative -PcmakeArgs=\"-DQT_ANDROID_DIR=...\"" >&2
    exit 1
fi
echo "源产物: $SO"

TMP="$ROOT/build/jni_stripped.so"
mkdir -p "$ROOT/build"
"$STRIP" --strip-unneeded "$SO" -o "$TMP"

cp "$TMP" "$ROOT/third_party/android-native-libs/libreverie_jni.so"
mkdir -p "$ROOT/app/src/main/jniLibs/arm64-v8a"
cp "$TMP" "$ROOT/app/src/main/jniLibs/arm64-v8a/libreverie_jni.so"
rm -f "$TMP"

echo "已刷新: third_party/android-native-libs/libreverie_jni.so ($(du -h "$ROOT/third_party/android-native-libs/libreverie_jni.so" | cut -f1))"
echo "关键 JNI 符号核对:"
for sym in animationEnabled configureOnionSkin anyLayerOnionSkin importKeyframeFromBitmap storeRevAsset revAssetNames; do
    n="$(nm -D "$ROOT/third_party/android-native-libs/libreverie_jni.so" | grep -c "ReverieCoreBridge_${sym}" || true)"
    echo "  ${sym} = ${n}"
done
echo "提示: third_party 里的 .so 受 git 跟踪, 刷新后记得提交。"
