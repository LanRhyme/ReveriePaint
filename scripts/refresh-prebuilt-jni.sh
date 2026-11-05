#!/usr/bin/env bash
#
# SPDX-License-Identifier: GPL-3.0-or-later
#
# 把最新的原生构建产物 strip 后刷新到预编译库。
#
# 为什么要这个脚本:
#   本仓库默认走"预编译 jni"模式 —— 构建时把 third_party/android-native-libs/
#   里的 libreverie_{jni,core}.so (以及全部 Krita/Qt 动态库) 拷进 src/main/jniLibs 打包。
#   如果这份预编译库落后于 C++ 源码 (例如新增了 JNI 方法却没刷新它),
#   打包出来的 APK 一调用新方法就 UnsatisfiedLinkError 崩溃
#   (例如"创建动画项目崩溃: animationEnabled 无实现")。
#   注意 src/main/jniLibs 里的副本会顶掉 CMake 产物, 所以它也必须一起刷新。
#
#   拆库后 (docs/plans/CORE-SPLIT.md) 两个库必须**同时**刷新:
#   libreverie_jni.so 通过 NEEDED 依赖 libreverie_core.so, 只换一个是
#   "新壳配旧引擎", 符号门禁也查不出来 (2026-10-31 修脚本前的缺口)。
#
# 用法: ./scripts/refresh-prebuilt-jni.sh
#   (先跑一次 ./gradlew assembleDebug -PbuildNative 生成产物;
#    完整闭包构建用 ./scripts/build_native.sh, 32 位用 ./scripts/build_native.sh --abi armeabi-v7a)
#   可用 RV_OBJ_DIR 显式指定 CMake 产物目录。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/25.2.9519653}"
STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"

if [ ! -x "$STRIP" ]; then
	echo "找不到 llvm-strip: $STRIP (设置 ANDROID_NDK_HOME 指向 NDK 25.2.9519653)" >&2
	exit 1
fi

OBJ_DIR="${RV_OBJ_DIR:-}"
if [ -z "$OBJ_DIR" ]; then
	OBJ_DIR="$(ls -d "$ROOT"/app/build/intermediates/cxx/Release/*/obj/arm64-v8a 2>/dev/null | head -1 || true)"
fi
if [ -z "$OBJ_DIR" ]; then
	OBJ_DIR="$(ls -d "$ROOT"/app/build/intermediates/cmake/*/obj/arm64-v8a 2>/dev/null | head -1 || true)"
fi
if [ -z "$OBJ_DIR" ] || [ ! -d "$OBJ_DIR" ]; then
	echo "找不到 CMake 产物; 先执行: ./gradlew assembleDebug -PbuildNative" >&2
	exit 1
fi
echo "源产物目录: $OBJ_DIR"

TMP="$ROOT/build/jni_stripped.so"
mkdir -p "$ROOT/build"
mkdir -p "$ROOT/app/src/main/jniLibs/arm64-v8a"

for lib in libreverie_jni.so libreverie_core.so; do
	SO="$OBJ_DIR/$lib"
	if [ ! -f "$SO" ]; then
		echo "缺少产物: $SO" >&2
		exit 1
	fi
	"$STRIP" --strip-unneeded "$SO" -o "$TMP"
	cp "$TMP" "$ROOT/third_party/android-native-libs/$lib"
	cp "$TMP" "$ROOT/app/src/main/jniLibs/arm64-v8a/$lib"
	echo "已刷新: third_party/android-native-libs/$lib ($(du -h "$ROOT/third_party/android-native-libs/$lib" | cut -f1))"
done
rm -f "$TMP"

echo "关键 JNI 符号核对:"
for sym in animationEnabled configureOnionSkin anyLayerOnionSkin importKeyframeFromBitmap storeRevAsset revAssetNames; do
	n="$(nm -D "$ROOT/third_party/android-native-libs/libreverie_jni.so" | grep -c "ReverieCoreBridge_${sym}" || true)"
	echo "  ${sym} = ${n}"
done
echo "下一步: ./scripts/check_jni_symbols.sh"
echo "提示: third_party 里的 .so 受 git 跟踪, 刷新后记得提交。"
