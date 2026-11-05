#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# 预编译闭包的「动态符号可解析性」门禁。
#
# check_jni_symbols.sh 只对账 Kotlin external fun 与 .so 的导出符号, 看不见 .so 之间
# 的 C/C++ 依赖: 1.6.2 的 armeabi-v7a 包正是因为
#   libkritacolorsmudgepaintop.so 引用 ReveriePigmentMix::isEnabled()
#   而闭包里没有任何库定义它
# 导致所有 32 位安装启动即 UnsatisfiedLinkError。本脚本补这一层:
# 对每个 ABI 的闭包求「强未定义符号 - 闭包内已定义 - Android 平台库导出」,
# 剩下的必须为空, 否则打包前直接失败。
#
# 用法:
#   ./scripts/check_native_symbols.sh                 # 两个 ABI 全查
#   ./scripts/check_native_symbols.sh armeabi-v7a     # 只查 32 位
#   ALLOW_UNRESOLVED='sym1|sym2' ./scripts/check_native_symbols.sh   # 已知例外(慎用)
#
# 退出码: 0 = 全部可解析; 1 = 有未解析符号 (逐条打印 库 -> 符号)。
set -uo pipefail
cd "$(dirname "$0")/.."

# NDK 只用来取 Android 平台库的导出符号, 必须用带平台 .so stub 的版本
# (r26 及以前; r27+ 的 sysroot 只剩静态 stub, 拿不到符号表)。
# 优先用项目固定的 25.2.9519653, 其次在已装 NDK 里找第一个可用的。
find_ndk() {
	local c base cand pinned="25.2.9519653"
	for c in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}"; do
		[ -n "$c" ] && [ -d "$c" ] && { echo "$c"; return; }
	done
	for base in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk"; do
		[ -n "$base" ] || continue
		[ -d "$base/ndk/$pinned" ] && { echo "$base/ndk/$pinned"; return; }
	done
	for base in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk"; do
		[ -n "$base" ] || continue
		for cand in $(ls -d "$base"/ndk/* 2>/dev/null | sort -V -r); do
			if [ -f "$cand/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/21/libc.so" ]; then
				echo "$cand"
				return
			fi
		done
	done
}
NDK="$(find_ndk)"
if [ -z "$NDK" ]; then
	echo "找不到可用 NDK (需要 r26 及以前、带 sysroot .so stub 的版本; 本项目固定 25.2.9519653)" >&2
	echo "本地: 设置 ANDROID_NDK_HOME; CI: GitHub runner 自带 25.2.9519653 (ANDROID_HOME/ndk)" >&2
	exit 2
fi
LLVM_BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
NM="$LLVM_BIN/llvm-nm"
if [ ! -x "$NM" ]; then
	NM="$(command -v llvm-nm || command -v nm)"
fi
SYSROOT="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib"

declare -A ABI_DIR=(
	[arm64-v8a]=third_party/android-native-libs
	[armeabi-v7a]=third_party/android-native-libs-armeabi-v7a
)
declare -A ABI_TRIPLE=(
	[arm64-v8a]=aarch64-linux-android
	[armeabi-v7a]=arm-linux-androideabi
)

targets=("$@")
if [ ${#targets[@]} -eq 0 ]; then
	targets=(arm64-v8a armeabi-v7a)
fi

# nm 输出: 已定义 "addr T sym" / 未定义 "         U sym" / 弱未定义 "         w sym"
symbols_of() {
	local mode="$1" file="$2"
	if [ "$mode" = "defined" ]; then
		"$NM" -D --defined-only "$file" 2>/dev/null |
			awk 'NF>=3 {print $3}' | sed 's/@.*//'
	else
		"$NM" -D --undefined-only "$file" 2>/dev/null |
			awk '$1=="U" {print $2}' | sed 's/@.*//'
	fi
}

# Android 平台库的导出符号: 取 NDK sysroot 里所有 API 版本的并集, 避免
# "__write_chk 只在 API 24+ 的 stub 里" 这类版本差异造成误报。
system_symbols() {
	local abi="$1" triple="${ABI_TRIPLE[$abi]}" api dir f
	for api in $(ls "$SYSROOT/$triple" 2>/dev/null | sort -n); do
		dir="$SYSROOT/$triple/$api"
		[ -d "$dir" ] || continue
		for f in "$dir"/libc.so "$dir"/libm.so "$dir"/libdl.so "$dir"/liblog.so \
			"$dir"/libandroid.so "$dir"/libjnigraphics.so "$dir"/libz.so \
			"$dir"/libGLESv2.so "$dir"/libEGL.so "$dir"/libOpenSLES.so \
			"$dir"/libc++_shared.so "$dir"/libc++abi.so "$dir"/libunwindstack.so \
			"$dir"/libnativehelper.so "$dir"/libvulkan.so; do
			[ -f "$f" ] || continue
			symbols_of defined "$f"
		done
	done | sort -u
}

fail=0
for abi in "${targets[@]}"; do
	dir="${ABI_DIR[$abi]:-}"
	if [ -z "$dir" ]; then
		echo "未知 ABI: $abi (可选 arm64-v8a / armeabi-v7a)" >&2
		exit 2
	fi
	if ! ls "$dir"/*.so >/dev/null 2>&1; then
		echo "[$abi] 跳过: $dir 下没有 .so" >&2
		continue
	fi

	tmp="$(mktemp -d)"
	: >"$tmp/defined.all"
	: >"$tmp/undefined.all"
	: >"$tmp/refs"
	for f in "$dir"/*.so; do
		symbols_of defined "$f" >>"$tmp/defined.all"
		symbols_of undefined "$f" >"$tmp/u.one"
		cat "$tmp/u.one" >>"$tmp/undefined.all"
		awk -v lib="$(basename "$f")" 'NF {print $0 "\t" lib}' "$tmp/u.one" >>"$tmp/refs"
	done
	sort -u "$tmp/defined.all" >"$tmp/defined"
	sort -u "$tmp/undefined.all" >"$tmp/undefined"
	system_symbols "$abi" >"$tmp/system"

	comm -23 "$tmp/undefined" "$tmp/defined" |
		comm -23 - "$tmp/system" | sed '/^$/d' >"$tmp/missing"

	if [ -n "${ALLOW_UNRESOLVED:-}" ]; then
		grep -Ev "(${ALLOW_UNRESOLVED})" "$tmp/missing" >"$tmp/missing.filtered" || true
		mv "$tmp/missing.filtered" "$tmp/missing"
	fi

	if [ -s "$tmp/missing" ]; then
		fail=1
		echo "✗ [$abi] 有 $(wc -l <"$tmp/missing") 个符号在闭包里解析不到:" >&2
		while IFS= read -r sym; do
			refs="$(grep -F "$(printf '%s\t' "$sym")" "$tmp/refs" | cut -f2 | sort -u | paste -sd, -)"
			echo "    ${refs:-?} -> $sym" >&2
		done <"$tmp/missing"
	else
		echo "✓ [$abi] 闭包符号全部可解析 ($(ls -1 "$dir"/*.so | wc -l) 个 .so)"
	fi
	rm -rf "$tmp"
done

if [ "$fail" -ne 0 ]; then
	echo "" >&2
	echo "修复方向: 重新编译对应 ABI 的闭包 (./scripts/build_native.sh --abi <abi>), 不要手工塞源码树外的 .so。" >&2
fi
exit "$fail"
