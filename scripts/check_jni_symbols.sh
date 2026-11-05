#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# JNI 符号覆盖门禁
#
# 背景: 本仓库默认走「预编译 jni」模式 —— APK 里的 libreverie_jni.so 直接来自
# third_party/android-native-libs[-<abi>]/, 不参与编译。只要这份快照落后于
# Kotlin 侧 ReverieCoreBridge.kt 的 external fun, 打包出来的 APK 一调用新方法
# 就 UnsatisfiedLinkError 崩溃, 而且是 FATAL (历史: 2026-09-15 animationEnabled、
# 2026-10-17 beginRenderTiles、2026-10-31 nativeSnapshotMode 三次同型事故)。
#
# 本脚本把「Kotlin 声明的方法」与「每个 ABI 的 .so 实际导出的符号」逐一对账,
# 任何缺口直接非零退出, 让 CI 在打包前失败, 而不是让用户装到崩溃包。
#
# 用法:
#   scripts/check_jni_symbols.sh                 # 检查 third_party 全部 ABI + jniLibs 副本
#   scripts/check_jni_symbols.sh armeabi-v7a     # 只查某个 ABI
#   scripts/check_jni_symbols.sh --strict-extra  # 额外把「已退役但仍导出」的符号判失败
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BRIDGE="$ROOT/app/src/main/java/com/reverie/paint/core/ReverieCoreBridge.kt"
JNI_PREFIX="Java_com_reverie_paint_core_ReverieCoreBridge_"

STRICT_EXTRA=0
ABI_FILTER=()
for arg in "$@"; do
	case "$arg" in
		--strict-extra) STRICT_EXTRA=1 ;;
		-h|--help)
			sed -n '2,20p' "$0"
			exit 0
			;;
		*) ABI_FILTER+=("$arg") ;;
	esac
done

if [ ! -f "$BRIDGE" ]; then
	echo "找不到 $BRIDGE" >&2
	exit 1
fi

# ---- 1. 期望符号表: 从 Kotlin external fun 生成 ----
EXPECTED_LIST="$(mktemp)"
trap 'rm -f "$EXPECTED_LIST"' EXIT

grep -oE 'external fun [A-Za-z0-9_]+' "$BRIDGE" | awk '{print $3}' | sort -u |
	while read -r name; do
		echo "${JNI_PREFIX}${name}"
	done > "$EXPECTED_LIST"

EXPECTED_COUNT="$(wc -l < "$EXPECTED_LIST" | tr -d ' ')"
if [ "$EXPECTED_COUNT" -eq 0 ]; then
	echo "未能从 $BRIDGE 解析出任何 external fun, 检查格式变化" >&2
	exit 1
fi

# 重载会让 JNI 名字带上参数签名后缀, 本仓库约定不做重载 —— 有重复就提醒
DUPES="$(grep -oE 'external fun [A-Za-z0-9_]+' "$BRIDGE" | awk '{print $3}' | sort | uniq -d)"
if [ -n "$DUPES" ]; then
	echo "警告: 存在重名 external fun (JNI 会带签名后缀, 本脚本无法对账):" >&2
	echo "$DUPES" | sed 's/^/  - /' >&2
fi

# ---- 2. 待检查目录 ----
# third_party/android-native-libs            -> arm64-v8a
# third_party/android-native-libs-<abi>      -> <abi>
declare -a TARGET_ABI=()
declare -a TARGET_DIR=()

add_target() {
	local abi="$1" dir="$2"
	if [ ${#ABI_FILTER[@]} -gt 0 ]; then
		local hit=0
		for want in "${ABI_FILTER[@]}"; do
			[ "$want" = "$abi" ] && hit=1
		done
		[ "$hit" = 1 ] || return 0
	fi
	TARGET_ABI+=("$abi")
	TARGET_DIR+=("$dir")
}

add_target "arm64-v8a" "$ROOT/third_party/android-native-libs"
for d in "$ROOT"/third_party/android-native-libs-*; do
	[ -d "$d" ] || continue
	add_target "$(basename "$d" | sed 's/^android-native-libs-//')" "$d"
done

if [ ${#TARGET_ABI[@]} -eq 0 ]; then
	echo "没有匹配的 ABI (可用: arm64-v8a armeabi-v7a)" >&2
	exit 1
fi

# ---- 3. 逐个 ABI 对账 ----
export_symbols() {
	local so="$1" out="$2"
	if command -v nm >/dev/null 2>&1; then
		nm -D --defined-only "$so" 2>/dev/null |
			grep -oE "${JNI_PREFIX}[A-Za-z0-9_]+" | sort -u > "$out"
	else
		readelf -sW --dyn-syms "$so" 2>/dev/null |
			grep -oE "${JNI_PREFIX}[A-Za-z0-9_]+" | sort -u > "$out"
	fi
}

check_dir() {
	local abi="$1" dir="$2" label="$3"
	local so="$dir/libreverie_jni.so"

	if [ ! -f "$so" ]; then
		echo "✗ [$abi/$label] 缺少 libreverie_jni.so ($dir)"
		return 1
	fi

	local actual_list
	actual_list="$(mktemp)"
	export_symbols "$so" "$actual_list"

	if [ ! -s "$actual_list" ]; then
		echo "✗ [$abi/$label] 无法从 $so 读取导出符号 (nm/readelf 失败或库为空)"
		rm -f "$actual_list"
		return 1
	fi

	local missing extra
	missing="$(comm -23 "$EXPECTED_LIST" "$actual_list")"
	extra="$(comm -13 "$EXPECTED_LIST" "$actual_list")"
	local missing_count extra_count
	missing_count="$(printf '%s' "$missing" | grep -c . || true)"
	extra_count="$(printf '%s' "$extra" | grep -c . || true)"
	local actual_count
	actual_count="$(wc -l < "$actual_list" | tr -d ' ')"
	rm -f "$actual_list"

	local rc=0
	if [ "$missing_count" -gt 0 ]; then
		echo "✗ [$abi/$label] $missing_count 个 JNI 符号未导出 (Kotlin 有声明, .so 没有 → 调用即 UnsatisfiedLinkError)"
		printf '%s\n' "$missing" | head -25 | sed "s/^${JNI_PREFIX}/    /;s/$/()/"
		[ "$missing_count" -gt 25 ] && echo "    ...(还有 $((missing_count - 25)) 个)"
		echo "    修复: 重新编译该 ABI 的 C++ 并刷新 $dir/"
		rc=1
	else
		echo "✓ [$abi/$label] $EXPECTED_COUNT/$EXPECTED_COUNT 个 JNI 符号齐全 (导出符号总数 $actual_count)"
	fi

	if [ "$extra_count" -gt 0 ]; then
		if [ "$STRICT_EXTRA" = 1 ]; then
			echo "✗ [$abi/$label] $extra_count 个已退役符号仍被导出"
			printf '%s\n' "$extra" | head -10 | sed "s/^${JNI_PREFIX}/    /;s/$/()/"
			rc=1
		else
			echo "  [$abi/$label] 提示: $extra_count 个已退役符号仍在导出 (无害)"
		fi
	fi

	# 拆库后 libreverie_jni.so 通过 NEEDED 依赖 libreverie_core.so, 少一个就起不来
	if command -v readelf >/dev/null 2>&1; then
		local need
		while read -r need; do
			[ -n "$need" ] || continue
			if [ ! -f "$dir/$need" ]; then
				echo "✗ [$abi/$label] libreverie_jni.so 需要 $need, 但 $dir/ 里没有"
				rc=1
			fi
		done < <(readelf -d "$so" 2>/dev/null | grep NEEDED | grep -oE 'libreverie_[A-Za-z0-9_]+\.so' | sort -u)
	fi

	return "$rc"
}

echo "期望 JNI 符号: $EXPECTED_COUNT 个 (来自 ReverieCoreBridge.kt)"
FAILED=0
for i in "${!TARGET_ABI[@]}"; do
	abi="${TARGET_ABI[$i]}"
	dir="${TARGET_DIR[$i]}"
	check_dir "$abi" "$dir" "third_party" || FAILED=1

	# 打包副本: 若存在就必须与之一致 (本地构建忘跑 copy_jni_libs.sh 时能拦住)
	jni_dir="$ROOT/app/src/main/jniLibs/$abi"
	if [ -f "$jni_dir/libreverie_jni.so" ]; then
		check_dir "$abi" "$jni_dir" "jniLibs" || FAILED=1
	fi
done

if [ "$FAILED" -ne 0 ]; then
	echo
	echo "JNI 符号对账失败: 请先把对应 ABI 的 C++ 重新编译并刷新预编译库"
	echo "  arm64-v8a  : ./scripts/build_native.sh && ./scripts/refresh-prebuilt-jni.sh"
	echo "  armeabi-v7a: ./scripts/build_native.sh --abi armeabi-v7a"
	exit 1
fi

echo "全部 ABI 的 JNI 符号对账通过"
