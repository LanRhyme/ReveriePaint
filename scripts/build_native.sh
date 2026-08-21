#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later

# 开发者模式完整构建: 重新编译 C++ (reverie_jni) 并打包全部动态库
#
# AGP 9 只自动收集 CMake NEEDED 闭包的一部分 (libreverie_jni.so 直接链接的),
# 其余库 (paintop 插件/KF6/Qt 间接依赖等) 需要手动补齐:
#   1. 第一次构建: CMake 编译 jni, 生成闭包产物
#   2. 补齐缺失库到 jniLibsNativeEmpty (构建时合并, 与闭包不重叠)
#   3. 第二次构建: 完整 APK
#
# 前置: 本机需具备 Qt for Android 6.6.3 + Krita 源码 + KF6 环境
#   (预编译模式不需要本脚本, 直接 ./gradlew assembleDebug 即可)
set -e
cd "$(dirname "$0")/.."

echo "== 1/3 清空 jniLibsNativeEmpty =="
rm -rf app/src/main/jniLibsNativeEmpty 2>/dev/null || true
mkdir -p app/src/main/jniLibsNativeEmpty/arm64-v8a

echo "== 2/3 第一次构建 (CMake 编译 reverie_jni) =="
./gradlew assembleDebug --no-daemon -PbuildNative

echo "== 3/3 补齐缺失动态库 =="
OBJ_DIR="$(ls -d app/build/intermediates/cxx/Release/*/obj/arm64-v8a 2>/dev/null | head -1)"
if [ -z "$OBJ_DIR" ]; then
	echo "错误: 未找到 CMake 产物目录"
	exit 1
fi
count=0
for f in third_party/android-native-libs/*.so; do
	b="$(basename "$f")"
	if [ -f "$OBJ_DIR/$b" ]; then
		continue
	fi
	cp -f "$f" app/src/main/jniLibsNativeEmpty/arm64-v8a/
	count=$((count + 1))
done
echo "补齐 $count 个库到 jniLibsNativeEmpty (跳过 CMake 闭包已有)"

echo "== 再次构建完整 APK =="
./gradlew assembleDebug --no-daemon -PbuildNative
echo "完成: app/build/outputs/apk/debug/app-debug.apk"
