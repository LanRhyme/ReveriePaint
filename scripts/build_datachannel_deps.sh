#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# 交叉编译 MbedTLS (3.6 LTS) + libdatachannel (静态库, NO_MEDIA + NO_WEBSOCKET)
# 并安装至 ReveriePaint 原生依赖前缀:
#   arm64-v8a   -> ~/ReveriePaint-android/deps
#   armeabi-v7a -> ~/ReveriePaint-android/deps-armv7
#
# 用法:
#   ./scripts/build_datachannel_deps.sh                # 构建 arm64-v8a 与 armeabi-v7a
#   ./scripts/build_datachannel_deps.sh arm64-v8a      # 只构建指定 ABI
set -euo pipefail

MBEDTLS_TAG="${MBEDTLS_TAG:-v3.6.8}"
LIBDATACHANNEL_TAG="${LIBDATACHANNEL_TAG:-v0.24.8}"

NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/25.2.9519653}"
TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
SRC_ROOT="${RV_DC_SRC_ROOT:-$HOME/ReveriePaint-android/src}"
BUILD_ROOT="${RV_DC_BUILD_ROOT:-$HOME/ReveriePaint-android/build-dc}"

if [ ! -f "$TOOLCHAIN" ]; then
	echo "找不到 Android NDK toolchain: $TOOLCHAIN" >&2
	exit 1
fi

ABIS=("$@")
if [ ${#ABIS[@]} -eq 0 ]; then
	ABIS=("arm64-v8a" "armeabi-v7a")
fi

mkdir -p "$SRC_ROOT" "$BUILD_ROOT"

clone_if_missing() {
	local dir="$1" url="$2" tag="$3"
	if [ ! -d "$dir/.git" ]; then
		echo "== 克隆 $url ($tag) -> $dir =="
		git clone --depth 1 --branch "$tag" --recursive "$url" "$dir"
	else
		echo "== 复用已有源码: $dir =="
	fi
}

clone_if_missing "$SRC_ROOT/mbedtls" "https://github.com/Mbed-TLS/mbedtls.git" "$MBEDTLS_TAG"
clone_if_missing "$SRC_ROOT/libdatachannel" "https://github.com/paullouisageneau/libdatachannel.git" "$LIBDATACHANNEL_TAG"

# libdatachannel 启用 USE_MBEDTLS 时, dtlstransport.cpp 无条件引用了 mbedtls_ssl_srtp_profile
if [ -x "$SRC_ROOT/mbedtls/scripts/config.py" ]; then
	python3 "$SRC_ROOT/mbedtls/scripts/config.py" \
		-f "$SRC_ROOT/mbedtls/include/mbedtls/mbedtls_config.h" \
		set MBEDTLS_SSL_DTLS_SRTP
fi

build_for_abi() {
	local abi="$1"
	local prefix
	if [ "$abi" = "arm64-v8a" ]; then
		prefix="$HOME/ReveriePaint-android/deps"
	elif [ "$abi" = "armeabi-v7a" ]; then
		prefix="$HOME/ReveriePaint-android/deps-armv7"
	else
		echo "不支持的 ABI: $abi" >&2
		exit 1
	fi

	mkdir -p "$prefix"

	echo "========================================"
	echo "构建 MbedTLS ($MBEDTLS_TAG) [$abi] -> $prefix"
	echo "========================================"
	local mbed_build="$BUILD_ROOT/mbedtls-$abi"
	rm -rf "$mbed_build"
	cmake -S "$SRC_ROOT/mbedtls" -B "$mbed_build" -G Ninja \
		-DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
		-DANDROID_ABI="$abi" \
		-DANDROID_PLATFORM=android-23 \
		-DANDROID_STL=c++_shared \
		-DCMAKE_BUILD_TYPE=Release \
		-DCMAKE_POSITION_INDEPENDENT_CODE=ON \
		-DCMAKE_INSTALL_PREFIX="$prefix" \
		-DENABLE_TESTING=OFF \
		-DENABLE_PROGRAMS=OFF \
		-DUSE_SHARED_MBEDTLS_LIBRARY=OFF \
		-DUSE_STATIC_MBEDTLS_LIBRARY=ON
	cmake --build "$mbed_build" -j "$(nproc)"
	cmake --install "$mbed_build"

	echo "========================================"
	echo "构建 libdatachannel ($LIBDATACHANNEL_TAG) [$abi] -> $prefix"
	echo "========================================"
	local dc_build="$BUILD_ROOT/libdatachannel-$abi"
	rm -rf "$dc_build"
	cmake -S "$SRC_ROOT/libdatachannel" -B "$dc_build" -G Ninja \
		-DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
		-DANDROID_ABI="$abi" \
		-DANDROID_PLATFORM=android-23 \
		-DANDROID_STL=c++_shared \
		-DCMAKE_BUILD_TYPE=Release \
		-DCMAKE_POSITION_INDEPENDENT_CODE=ON \
		-DCMAKE_INSTALL_PREFIX="$prefix" \
		-DCMAKE_PREFIX_PATH="$prefix" \
		-DCMAKE_FIND_ROOT_PATH="$prefix" \
		-DBUILD_SHARED_LIBS=OFF \
		-DNO_MEDIA=ON \
		-DNO_WEBSOCKET=ON \
		-DUSE_MBEDTLS=ON \
		-DUSE_GNUTLS=OFF \
		-DUSE_NICE=OFF \
		-DNO_TESTS=ON \
		-DNO_EXAMPLES=ON
	cmake --build "$dc_build" -j "$(nproc)"
	cmake --install "$dc_build"

	# 静态库打包模式下, 子模块 libjuice / usrsctp 静态库可能未由 install 导出到 prefix/lib, 补全拷贝
	find "$dc_build/deps" -name 'lib*.a' -exec cp -f {} "$prefix/lib/" \; || true
	ls -lh "$prefix/lib"/libdatachannel*.a "$prefix/lib"/libmbed*.a "$prefix/lib"/libjuice*.a "$prefix/lib"/libusrsctp*.a 2>/dev/null || true
}

for abi in "${ABIS[@]}"; do
	build_for_abi "$abi"
done

echo "完成 MbedTLS + libdatachannel 双 ABI 依赖构建"
