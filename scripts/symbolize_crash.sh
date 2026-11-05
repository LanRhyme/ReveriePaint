#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# 把崩溃报告里的 "模块+偏移" 还原成函数名。
#
# 用法:
#   ./scripts/symbolize_crash.sh <crash_native_*.log>            # 直接给一份落盘日志
#   ./scripts/symbolize_crash.sh --version 48 <crash_*.log>      # 从归档缓存里取对应版本的库
#   ./scripts/symbolize_crash.sh <libreverie_core.so> 0x3d07c4 0x2c10
#   SYMBOLIZE_VERBOSE=1 ./scripts/symbolize_crash.sh <log>       # 同时打印执行的命令
#
# 前提: 需要有"未 strip"的 .so。官方 Release 用的是 third_party 里已 strip 的预编译库,
# 所以符号化只对本地用 -PbuildNative 构建出来的那份产物有效 —— 崩溃看板里的偏移必须
# 和构建那份 .so 的源码版本(app.versionCode)对得上。
set -euo pipefail
cd "$(dirname "$0")/.."

find_tool() {
	local name="$1"
	if [[ -n "${ANDROID_HOME:-}" ]] && compgen -G "$ANDROID_HOME/ndk/*/toolchains/llvm/prebuilt/*/bin/$name" >/dev/null; then
		ls -1 "$ANDROID_HOME"/ndk/*/toolchains/llvm/prebuilt/*/bin/"$name" 2>/dev/null | sort -V | tail -1
		return
	fi
	local sdk="$HOME/Android/Sdk"
	if compgen -G "$sdk/ndk/*/toolchains/llvm/prebuilt/*/bin/$name" >/dev/null; then
		ls -1 "$sdk"/ndk/*/toolchains/llvm/prebuilt/*/bin/"$name" 2>/dev/null | sort -V | tail -1
		return
	fi
	command -v "$name" || command -v addr2line || true
}

ADDR2LINE="$(find_tool llvm-addr2line)"
if [[ -z "$ADDR2LINE" ]]; then
	echo "错误: 找不到 llvm-addr2line / addr2line" >&2
	exit 1
fi

# 找库的顺序: --version 指定的归档缓存 -> 构建产物 -> 预编译闭包
SYMBOL_VERSION="${SYMBOL_VERSION:-}"
CACHE_DIR="${REVP_SYMBOL_CACHE:-$HOME/.cache/revp-symbols}"

# 本机没有这个版本的库时, 从 Worker 的 KV 里取(gzip 传输, 落盘解压)
# 需要 REVP_STATS_KEY; 端点可用 REVP_TELEMETRY_ENDPOINT 覆盖
fetch_remote_symbol() {
	local ver="$1" base="$2"
	[[ -n "${REVP_STATS_KEY:-}" ]] || return 1
	local endpoint="${REVP_TELEMETRY_ENDPOINT:-https://telemetry.lanrhyme.top}"
	local dest="$CACHE_DIR/$ver"
	mkdir -p "$dest"
	local gz="$dest/$base.gz"
	echo "  取回 $ver/$base ..." >&2
	curl -fsSL --max-time 600 "$endpoint/t/v1/symbols/$ver/$base?key=$REVP_STATS_KEY" -o "$gz" || { rm -f "$gz"; return 1; }
	gunzip -f "$gz" || return 1
	[[ -f "$dest/$base" ]] || return 1
	echo "  已缓存到 $dest/$base" >&2
}

find_module() {
	local base="$1"
	local hit
	if [[ -n "$SYMBOL_VERSION" ]]; then
		hit="$CACHE_DIR/$SYMBOL_VERSION/$base"
		[[ -f "$hit" ]] && { echo "$hit"; return; }
		# 只对自家产出的库尝试远程取: 系统库/第三方库在 KV 里本来就没有
		case "$base" in
			libreverie_core.so | libreverie_jni.so)
				if fetch_remote_symbol "$SYMBOL_VERSION" "$base" && [[ -f "$hit" ]]; then
					echo "$hit"
					return
				fi
				;;
		esac
	fi
	hit="$(ls -1t app/build/intermediates/cxx/Release/*/obj/arm64-v8a/"$base" 2>/dev/null | head -1 || true)"
	if [[ -z "$hit" ]]; then
		hit="$(find app/build/intermediates/cxx third_party/android-native-libs -name "$base" -type f 2>/dev/null | head -1 || true)"
	fi
	echo "$hit"
}

symbolize_module() {
	local so="$1"
	shift
	[[ -f "$so" ]] || { echo "  (跳过: 找不到未 strip 的 $so)"; return; }
	[[ -n "$(find "$so" -newermt '-1 year')" ]] || true
	local nm_tool
	nm_tool="$(find_tool llvm-nm)"
	if [[ -n "$nm_tool" ]] && ! "$nm_tool" --defined-only "$so" >/dev/null 2>&1; then
		echo "  (提示: 这份 $so 看起来已被 strip, 解析只会得到 ??)"
	fi
	local -a addrs=()
	for off in "$@"; do
		# 日志里写的是 "+0x3d07c4", addr2line 只吃 "0x3d07c4"
		local o="${off#+}"
		o="${o#0x}"
		addrs+=("0x$o")
	done
	[[ ${#addrs[@]} -eq 0 ]] && return
	[[ "${SYMBOLIZE_VERBOSE:-0}" == "1" ]] && echo "  \$ $ADDR2LINE -f -C -e $so ${addrs[*]}" >&2
	"$ADDR2LINE" -f -C -e "$so" "${addrs[@]}" | paste - - | sed 's/^\t//'
}

if [[ "${1:-}" == "--version" ]]; then
	SYMBOL_VERSION="${2:-}"
	shift 2 || true
	if [[ -z "$SYMBOL_VERSION" ]]; then
		echo "错误: --version 后面要跟 versionCode" >&2
		exit 1
	fi
fi

if [[ $# -eq 0 ]]; then
	echo "用法: $0 [--version <code>] <crash_native_*.log> | <module.so> <offset>..."
	exit 1
fi

if [[ "$1" == *.log ]]; then
	log="$1"
	[[ -f "$log" ]] || { echo "错误: 找不到 $log" >&2; exit 1; }
	echo "== $log =="
	# 逐行匹配 "#NN pc 0x…  +0x…  /path/libfoo.so"
	awk '/^#/ && / pc 0x/ {
		mod="-"; off="-";
		for (i = 1; i <= NF; i++) {
			if ($i ~ /^\+0x/) off = $i;
			if ($i ~ /\.so$/) mod = $i;
		}
		if (mod != "-" && off != "-") printf "%s %s\n", mod, off;
	}' "$log" | while read -r path off; do
		base="$(basename "$path")"
		so="$(find_module "$base")"
		printf '%s %s -> ' "$base" "$off"
		line="$(symbolize_module "${so:-$base}" "$off" | head -1)"
		echo "${line:-??}"
	done
	exit 0
fi

so="$1"
shift
if [[ ! -f "$so" ]]; then
	resolved="$(find_module "$(basename "$so")")"
	[[ -n "$resolved" ]] || { echo "错误: 找不到 $so (也没在构建产物里找到同名文件)" >&2; exit 1; }
	so="$resolved"
fi
echo "== $so =="
symbolize_module "$so" "$@"
