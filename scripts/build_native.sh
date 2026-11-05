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
#
# 用法:
#   ./scripts/build_native.sh                  重新编译 arm64-v8a (原路径, 走 gradle CMake)
#   ./scripts/build_native.sh --abi armeabi-v7a
#                                              重新编译 32 位: 独立 NDK+CMake 构建 (gradle 那份
#                                              jniLibsNativeEmpty 补齐逻辑只覆盖 arm64), 编完
#                                              自动重收 third_party 的 32 位闭包并跑符号门禁
set -e
cd "$(dirname "$0")/.."

TARGET_ABI="arm64-v8a"
while [ $# -gt 0 ]; do
	case "$1" in
		--abi)
			TARGET_ABI="${2:?--abi 需要一个 ABI 值 (arm64-v8a / armeabi-v7a)}"
			shift 2
			;;
		-h | --help)
			sed -n '2,30p' "$0"
			exit 0
			;;
		*)
			echo "未知参数: $1" >&2
			exit 2
			;;
	esac
done

if [ "$TARGET_ABI" = "armeabi-v7a" ]; then
	NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/25.2.9519653}"
	KRITA_BIN="${KRITA_BIN:-$HOME/Projects/krita-source/build-android-armv7}"
	BUILD_DIR="${RV_ARMV7_BUILD_DIR:-$HOME/rvbuild-armv7}"

	if [ ! -f "$NDK/build/cmake/android.toolchain.cmake" ]; then
		echo "找不到 NDK 工具链: $NDK" >&2
		exit 1
	fi
	if [ ! -d "$KRITA_BIN" ]; then
		echo "找不到 Krita armv7 构建目录: $KRITA_BIN" >&2
		exit 1
	fi

	echo "== 1/4 同步 Krita 静态库 (defaultpaintops) =="
	# libkritadefaultpaintops_static.a 被直接链进 libreverie_core.so。
	# 它若比源码旧 (Krita 同步后忘了重编), 链接期会冒出
	# "undefined symbol: KisPaintOpSettingsWidget::setImage" 这类假故障。
	cmake --build "$KRITA_BIN" --target kritadefaultpaintops_static -j "$(nproc)"

	echo "== 2/4 configure (armeabi-v7a / NDK toolchain) =="
	cmake -S app/src/main/cpp -B "$BUILD_DIR" -G Ninja \
		-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
		-DANDROID_ABI=armeabi-v7a \
		-DANDROID_PLATFORM=android-23 \
		-DANDROID_STL=c++_shared \
		-DCMAKE_BUILD_TYPE=Release

	echo "== 3/4 编译 libreverie_{core,jni}.so =="
	cmake --build "$BUILD_DIR" -j "$(nproc)"

	echo "== 4/4 重收 32 位预编译闭包并校验符号 =="
	RV_BUILD="$BUILD_DIR" ./scripts/collect_armv7_libs.sh
	./scripts/check_jni_symbols.sh armeabi-v7a
	exit 0
fi

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
