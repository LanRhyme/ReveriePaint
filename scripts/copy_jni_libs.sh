#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later

# 复制预编译 Android arm64 动态库到 jniLibs
# 默认从仓库内置 third_party/android-native-libs 复制全部 112 个库:
#   Krita 核心库 + 笔刷插件 + Qt for Android + KF6 + NDK 依赖 + 预编译 libreverie_jni.so
# 克隆仓库后直接运行本脚本即可构建 APK, 无需本地 Qt/Krita 环境
#
# 用法:
#   scripts/copy_jni_libs.sh             预编译模式(默认), 复制 arm64-v8a 含 libreverie_jni.so
#   scripts/copy_jni_libs.sh --no-jni    开发者模式(-PbuildNative), 跳过 libreverie_jni.so
#                                        避免与 CMake 重编译产物冲突
#   scripts/copy_jni_libs.sh <目录> [abi] 从自定义目录复制, 可选指定 abi (默认 arm64-v8a)
set -e

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SKIP_JNI=0
TARGET_ABI=""
CUSTOM_SRC=""

while [ $# -gt 0 ]; do
	case "$1" in
		--no-jni)
			SKIP_JNI=1
			shift
			;;
		--abi)
			TARGET_ABI="$2"
			shift 2
			;;
		arm64-v8a|armeabi-v7a)
			TARGET_ABI="$1"
			shift
			;;
		*)
			CUSTOM_SRC="$1"
			shift
			;;
	esac
done

copy_abi() {
	local abi="$1"
	local src_dir="$2"
	if [ ! -d "$src_dir" ] || ! ls "$src_dir"/*.so >/dev/null 2>&1; then
		echo "跳过 $abi: 未找到 $src_dir 或目录内无 .so 文件"
		return 0
	fi

	local dst="$REPO_ROOT/app/src/main/jniLibs/$abi"
	mkdir -p "$dst"
	# 先清空再复制: 闭包里已删除的库 (例如上游移除的 libkritacolor.so) 不能靠覆盖复制
	# 残留在 jniLibs 里随 APK 发出去 (1.6.2 闭包混旧库的同类问题)。
	find "$dst" -maxdepth 1 -name '*.so' -delete
	local count=0
	for f in "$src_dir"/*.so; do
		local b="$(basename "$f")"
		if [ "$SKIP_JNI" = "1" ] && [ "$b" = "libreverie_jni.so" ]; then
			rm -f "$dst/$b"
			continue
		fi
		cp -f "$f" "$dst/"
		count=$((count + 1))
	done
	echo "[$abi] 已复制 $count 个动态库到 $dst"
}

if [ -n "$CUSTOM_SRC" ]; then
	abi="${TARGET_ABI:-arm64-v8a}"
	copy_abi "$abi" "$CUSTOM_SRC"
elif [ -n "$TARGET_ABI" ]; then
	if [ "$TARGET_ABI" = "armeabi-v7a" ]; then
		copy_abi "armeabi-v7a" "$REPO_ROOT/third_party/android-native-libs-armeabi-v7a"
	else
		copy_abi "arm64-v8a" "$REPO_ROOT/third_party/android-native-libs"
	fi
else
	# 默认模式：自动拷贝所有具备预编译库的 ABI
	copy_abi "arm64-v8a" "$REPO_ROOT/third_party/android-native-libs"
	if [ -d "$REPO_ROOT/third_party/android-native-libs-armeabi-v7a" ]; then
		copy_abi "armeabi-v7a" "$REPO_ROOT/third_party/android-native-libs-armeabi-v7a"
	fi
fi

if [ "$SKIP_JNI" = "1" ]; then
	echo "已跳过 libreverie_jni.so (buildNative 模式由 CMake 重新编译)"
else
	echo "现在可以直接 ./gradlew assembleDebug 或 assembleRelease 构建"
fi
