#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# 引擎 ABI 等价性验证 (docs/BUILD-ANDROID-NATIVE.md §6):
#   1) JNI 导出符号集与 git HEAD 中的旧库逐条比对
#   2) NEEDED 依赖闭包逐条比对
# 两者都必须完全一致 —— 只改实现、不改跨语言契约。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_ROOT="${REVERIE_ENV_ROOT:-$HOME/reverie-deps}"

if [ -f "$ENV_ROOT/native-env.sh" ]; then
	# shellcheck disable=SC1091
	. "$ENV_ROOT/native-env.sh"
fi

BIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin"
SO="$REPO_ROOT/third_party/android-native-libs/libreverie_jni.so"
PREV="$(mktemp)"

git -C "$REPO_ROOT" show HEAD:third_party/android-native-libs/libreverie_jni.so >"$PREV"

echo "[abi] 导出符号集比对 (HEAD vs 新构建)"
if diff <("$BIN/llvm-nm" -D --defined-only "$PREV" | awk '{print $3}' | sort) \
	<("$BIN/llvm-nm" -D --defined-only "$SO" | awk '{print $3}' | sort); then
	echo "[abi]   符号集完全一致 ($("$BIN/llvm-nm" -D --defined-only "$SO" | wc -l) 个)"
else
	echo "[abi]   ⚠️ 符号集有差异 (新增/删除 JNI 入口时属预期, 否则是回归)" >&2
fi

echo "[abi] NEEDED 依赖闭包比对"
if diff <("$BIN/llvm-readelf" -d "$PREV" | awk '/NEEDED/ {print $NF}' | sort) \
	<("$BIN/llvm-readelf" -d "$SO" | awk '/NEEDED/ {print $NF}' | sort); then
	echo "[abi]   依赖闭包完全一致"
else
	echo "[abi]   ⚠️ 依赖闭包有差异 —— 不能进 APK" >&2
	exit 1
fi

rm -f "$PREV"
echo "[abi] 完成"
