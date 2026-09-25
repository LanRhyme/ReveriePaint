#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# 在 Linux/WSL 宿主上交叉编译 C++ 引擎 (等价于 Gradle 的 -PbuildNative 路径)。
#
# 为什么不用 scripts/build_native.sh: 那个脚本依赖 Windows 侧 Gradle+AGP 驱动 CMake,
# 而 CMakeLists.txt 的 NDK 路径按 Linux 宿主写死 (prebuilt/linux-x86_64)。本脚本直接
# 调 CMake, 用 NDK 自带的 android.toolchain.cmake, 产物路径与 AGP 一致。
#
# 前置: scripts/setup_native_env.sh 已跑过 (或本脚本会引导你跑)。
# 产物: app/src/main/jniLibs/arm64-v8a/libreverie_jni.so (CMake POST_BUILD 自动拷贝)
#
# 用法:
#   scripts/build_native_wsl.sh            # 增量构建
#   scripts/build_native_wsl.sh --clean    # 先删构建目录
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_ROOT="${REVERIE_ENV_ROOT:-$HOME/reverie-deps}"

if [ -f "$ENV_ROOT/native-env.sh" ]; then
	# shellcheck disable=SC1091
	. "$ENV_ROOT/native-env.sh"
else
	echo "错误: 未找到 $ENV_ROOT/native-env.sh, 请先运行 scripts/setup_native_env.sh --fetch" >&2
	exit 1
fi

BUILD_DIR="${BUILD_DIR:-$ENV_ROOT/jni-build}"

if [ "${1:-}" = "--clean" ]; then
	echo "[build-native-wsl] 清理 $BUILD_DIR"
	rm -rf "$BUILD_DIR"
fi

echo "[build-native-wsl] 配置 CMake (Android arm64-v8a, Release)"
cmake -S "$REPO_ROOT/app/src/main/cpp" -B "$BUILD_DIR" -G Ninja \
	-DCMAKE_TOOLCHAIN_FILE="$NDK_DIR/build/cmake/android.toolchain.cmake" \
	-DANDROID_ABI=arm64-v8a \
	-DANDROID_PLATFORM=android-24 \
	-DCMAKE_BUILD_TYPE=Release \
	-DANDROID_NDK="$NDK_DIR" \
	-DQT_ANDROID_DIR="$QT_ANDROID_DIR" \
	-DKRITA_SRC_DIR="$KRITA_SRC_DIR" \
	-DKRITA_BIN_DIR="$KRITA_BIN_DIR" \
	-DDEPS_DIR="$DEPS_DIR"

echo "[build-native-wsl] 编译"
cmake --build "$BUILD_DIR" --target reverie_jni -j "$(nproc)"

SO="$BUILD_DIR/libreverie_jni.so"
[ -f "$SO" ] || { echo "错误: 未生成 $SO" >&2; exit 1; }

STRIP="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
[ -x "$STRIP" ] || { echo "错误: 未找到 llvm-strip: $STRIP" >&2; exit 1; }

# 预编译模式走 third_party 里的 .so, 必须同步, 否则源码与二进制不一致;
# 同步前 strip (与仓库既有预编译库一致: 未 strip 约 40MB, strip 后约 3.7MB)
for dst in \
	"$REPO_ROOT/third_party/android-native-libs/libreverie_jni.so" \
	"$REPO_ROOT/app/src/main/jniLibs/arm64-v8a/libreverie_jni.so"; do
	cp -f "$SO" "$dst"
	"$STRIP" --strip-unneeded "$dst"
done

echo "[build-native-wsl] 完成: $SO"
ls -la "$SO"
ls -la "$REPO_ROOT/third_party/android-native-libs/libreverie_jni.so"
echo "[build-native-wsl] 已 strip 并同步到 third_party/android-native-libs 与 app/src/main/jniLibs/arm64-v8a"
