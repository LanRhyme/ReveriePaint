#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# 交叉编译并收集 Android 双架构 (arm64-v8a / armeabi-v7a) EasyTier FFI 动态库
#
# 产物:
#   - third_party/android-native-libs/libeasytier_ffi.so (arm64-v8a, stripped)
#   - third_party/android-native-libs-armeabi-v7a/libeasytier_ffi.so (armeabi-v7a, stripped)
#   - 同步写入 app/src/main/jniLibs/<abi>/ 与 $DEPS/lib/
#
# 前置:
#   - Rust (rustup, 将按源码 rust-toolchain.toml 使用 1.95) + target:
#       aarch64-linux-android, armv7-linux-androideabi
#   - Android NDK 25.2.9519653
#
# 用法:
#   ./scripts/build_easytier_deps.sh
#   ./scripts/build_easytier_deps.sh --abi arm64-v8a
#   ./scripts/build_easytier_deps.sh --abi armeabi-v7a
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EASYTIER_SRC="${EASYTIER_SRC:-$HOME/ReveriePaint-android/src/easytier}"
EASYTIER_FFI_DIR="$EASYTIER_SRC/easytier-contrib/easytier-ffi"
NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/25.2.9519653}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
STRIP="$TOOLCHAIN/bin/llvm-strip"
API_LEVEL=23

if [ ! -d "$EASYTIER_FFI_DIR" ]; then
    echo "找不到 EasyTier FFI 源码目录: $EASYTIER_FFI_DIR" >&2
    exit 1
fi
if [ ! -x "$STRIP" ]; then
    echo "找不到 NDK llvm-strip: $STRIP" >&2
    exit 1
fi

TARGET_ABIS=()
while [ $# -gt 0 ]; do
    case "$1" in
        --abi)
            TARGET_ABIS+=("$2")
            shift 2
            ;;
        arm64-v8a|armeabi-v7a)
            TARGET_ABIS+=("$1")
            shift
            ;;
        *)
            echo "未知参数: $1" >&2
            exit 1
            ;;
    esac
done
if [ ${#TARGET_ABIS[@]} -eq 0 ]; then
    TARGET_ABIS=(arm64-v8a armeabi-v7a)
fi

build_one() {
    local abi="$1"
    local rust_target clang_prefix bindgen_target prebuilt_dir deps_lib

    if [ "$abi" = "arm64-v8a" ]; then
        rust_target="aarch64-linux-android"
        clang_prefix="aarch64-linux-android${API_LEVEL}"
        bindgen_target=""
        prebuilt_dir="$ROOT/third_party/android-native-libs"
        deps_lib="${DEPS_ARM64:-$HOME/ReveriePaint-android/deps}/lib"
    elif [ "$abi" = "armeabi-v7a" ]; then
        rust_target="armv7-linux-androideabi"
        clang_prefix="armv7a-linux-androideabi${API_LEVEL}"
        bindgen_target="-target armv7a-linux-androideabi"
        prebuilt_dir="$ROOT/third_party/android-native-libs-armeabi-v7a"
        deps_lib="${DEPS_ARMV7:-$HOME/ReveriePaint-android/deps-armv7}/lib"
    else
        echo "不支持的 ABI: $abi" >&2
        exit 1
    fi

    local cc_bin="$TOOLCHAIN/bin/${clang_prefix}-clang"
    local cxx_bin="$TOOLCHAIN/bin/${clang_prefix}-clang++"
    local ar_bin="$TOOLCHAIN/bin/llvm-ar"
    local ranlib_bin="$TOOLCHAIN/bin/llvm-ranlib"

    if [ ! -x "$cc_bin" ]; then
        echo "找不到 clang: $cc_bin" >&2
        exit 1
    fi

    echo "== [$abi] 确保 Rust target $rust_target 已安装 =="
    (cd "$EASYTIER_SRC" && rustup target add "$rust_target" >/dev/null 2>&1 || true)

    local env_target="${rust_target//-/_}"
    local upper_target
    upper_target="$(printf '%s' "$env_target" | tr '[:lower:]' '[:upper:]')"

    echo "== [$abi] 编译 easytier-ffi ($rust_target release) =="
    (
        cd "$EASYTIER_FFI_DIR"
        export "CC_${env_target}=$cc_bin"
        export "CXX_${env_target}=$cxx_bin"
        export "AR_${env_target}=$ar_bin"
        export "RANLIB_${env_target}=$ranlib_bin"
        export "CARGO_TARGET_${upper_target}_LINKER=$cc_bin"
        export "CARGO_TARGET_${upper_target}_AR=$ar_bin"
        export "BINDGEN_EXTRA_CLANG_ARGS_${env_target}=--sysroot=$TOOLCHAIN/sysroot -D__ANDROID_API__=${API_LEVEL} ${bindgen_target}"
        cargo build --release --target "$rust_target"
    )

    local raw_so="$EASYTIER_SRC/target/$rust_target/release/libeasytier_ffi.so"
    if [ ! -f "$raw_so" ]; then
        echo "未找到编译产物: $raw_so" >&2
        exit 1
    fi

    mkdir -p "$prebuilt_dir" "$ROOT/app/src/main/jniLibs/$abi" "$deps_lib"
    "$STRIP" --strip-unneeded "$raw_so" -o "$prebuilt_dir/libeasytier_ffi.so"
    cp -f "$prebuilt_dir/libeasytier_ffi.so" "$ROOT/app/src/main/jniLibs/$abi/libeasytier_ffi.so"
    cp -f "$prebuilt_dir/libeasytier_ffi.so" "$deps_lib/libeasytier_ffi.so"
    echo "== [$abi] 已输出 stripped libeasytier_ffi.so ($(du -h "$prebuilt_dir/libeasytier_ffi.so" | awk '{print $1}')) =="
}

for abi in "${TARGET_ABIS[@]}"; do
    build_one "$abi"
done

"$ROOT/scripts/check_native_symbols.sh" "${TARGET_ABIS[@]}"
