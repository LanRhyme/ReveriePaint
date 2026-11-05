#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# 把本地 -PbuildNative 产出的「未 strip」动态库按 versionCode 归档到本机缓存,
# 之后线上崩溃的 "模块+偏移" 才符号化得出来。
#
# 用法:
#   ./scripts/archive_symbols.sh              # 版本号从 app/build.gradle.kts 读
#   ./scripts/archive_symbols.sh 49           # 或手动指定
#   ./scripts/archive_symbols.sh 49 --upload  # 同时推到 Worker 的 KV, 换机器也能符号化
#   ./scripts/archive_symbols.sh --list       # 看看归档了哪些版本
#
# 归档位置: ~/.cache/revp-symbols/<versionCode>/
#   libreverie_core.so / libreverie_jni.so / manifest.txt
#
# 注意: 只归档本项目自己产出的两个库。Qt/KF6/Krita 的库来自 Qt 官方与预编译闭包,
# 本地没有它们的未 strip 版本, 那部分帧只能看到偏移。
set -euo pipefail
cd "$(dirname "$0")/.."

CACHE="${REVP_SYMBOL_CACHE:-$HOME/.cache/revp-symbols}"
OWN_LIBS=(libreverie_core.so libreverie_jni.so)

if [[ "${1:-}" == "--list" ]]; then
	[[ -d "$CACHE" ]] || { echo "还没有归档 ($CACHE 不存在)"; exit 0; }
	echo "已归档版本:"
	for d in "$CACHE"/*/; do
		[[ -d "$d" ]] || continue
		code="$(basename "$d")"
		printf '  %-6s %s\n' "$code" "$(head -3 "$d/manifest.txt" 2>/dev/null | tr '\n' ' ' || echo '')"
	done
	exit 0
fi

UPLOAD=0
ARGS=()
for a in "$@"; do
	case "$a" in
		--upload) UPLOAD=1 ;;
		*) ARGS+=("$a") ;;
	esac
done
VERSION_CODE="${ARGS[0]:-}"
if [[ -z "$VERSION_CODE" ]]; then
	VERSION_CODE="$(grep -E '^[[:space:]]*versionCode = ' app/build.gradle.kts | head -1 | tr -dc '0-9')"
fi
VERSION_NAME="$(grep -E '^[[:space:]]*versionName = ' app/build.gradle.kts | head -1 | sed 's/.*"\(.*\)".*/\1/')"
if [[ -z "$VERSION_CODE" ]]; then
	echo "错误: 读不到 versionCode, 请手动传入" >&2
	exit 1
fi

# 取最新一次 CMake 产物目录
OBJ_DIR="$(ls -dt app/build/intermediates/cxx/Release/*/obj/arm64-v8a 2>/dev/null | head -1 || true)"
OBJ_DIR="${OBJ_DIR:-$(ls -dt app/build/intermediates/cxx/Debug/*/obj/arm64-v8a 2>/dev/null | head -1 || true)}"
if [[ -z "$OBJ_DIR" || ! -d "$OBJ_DIR" ]]; then
	echo "错误: 没找到 CMake 产物, 先跑 ./scripts/build_native.sh" >&2
	exit 1
fi

DEST="$CACHE/$VERSION_CODE"
mkdir -p "$DEST"

copied=0
for lib in "${OWN_LIBS[@]}"; do
	if [[ -f "$OBJ_DIR/$lib" ]]; then
		cp -f "$OBJ_DIR/$lib" "$DEST/$lib"
		copied=$((copied + 1))
	fi
done
if [[ "$copied" -eq 0 ]]; then
	echo "错误: $OBJ_DIR 里没有 ${OWN_LIBS[*]}" >&2
	exit 1
fi

# strip 过的库符号化只会得到 ??, 归档前先确认它还有符号表
check_tool=""
if [[ -n "${ANDROID_HOME:-}" ]] && compgen -G "$ANDROID_HOME/ndk/*/toolchains/llvm/prebuilt/*/bin/llvm-nm" >/dev/null; then
	check_tool="$(ls -1 "$ANDROID_HOME"/ndk/*/toolchains/llvm/prebuilt/*/bin/llvm-nm 2>/dev/null | sort -V | tail -1)"
elif compgen -G "$HOME/Android/Sdk/ndk/*/toolchains/llvm/prebuilt/*/bin/llvm-nm" >/dev/null; then
	check_tool="$(ls -1 "$HOME/Android/Sdk"/ndk/*/toolchains/llvm/prebuilt/*/bin/llvm-nm 2>/dev/null | sort -V | tail -1)"
fi
if [[ -n "$check_tool" ]]; then
	for lib in "${OWN_LIBS[@]}"; do
		[[ -f "$DEST/$lib" ]] || continue
		if ! "$check_tool" --defined-only "$DEST/$lib" >/dev/null 2>&1; then
			echo "警告: $lib 没有符号表(可能已被 strip), 归档了也符号化不出函数名" >&2
		fi
	done
fi

{
	echo "versionCode: $VERSION_CODE"
	echo "versionName: ${VERSION_NAME:-unknown}"
	echo "git: $(git rev-parse --short HEAD 2>/dev/null || echo unknown) $(git status --porcelain | wc -l | tr -d ' ') 个未提交改动"
	echo "built: $(date -Iseconds)"
	for lib in "${OWN_LIBS[@]}"; do
		[[ -f "$DEST/$lib" ]] || continue
		echo "$lib: $(md5sum "$DEST/$lib" | cut -d' ' -f1) $(du -h "$DEST/$lib" | cut -f1)"
	done
} > "$DEST/manifest.txt"

echo "已归档到 $DEST ($copied 个库)"
cat "$DEST/manifest.txt"

if [[ "$UPLOAD" == "1" ]]; then
	NS_ID="$(grep -A2 'binding = "SYMBOLS"' telemetry/worker/wrangler.toml | grep -E '^\s*id\s*=' | sed 's/.*"\(.*\)".*/\1/' | head -1)"
	if [[ -z "$NS_ID" ]]; then
		echo "错误: 读不到 SYMBOLS 的 KV namespace id" >&2
		exit 1
	fi
	echo "== 上传到 KV ($NS_ID) =="
	TMP_GZ="$(mktemp -t revp-sym.XXXXXX.gz)"
	for lib in "${OWN_LIBS[@]}"; do
		[[ -f "$DEST/$lib" ]] || continue
		gzip -9 -c "$DEST/$lib" > "$TMP_GZ"
		printf '  %-26s %s\n' "$lib" "$(du -h "$TMP_GZ" | cut -f1)"
		(cd telemetry/worker && env -u CLOUDFLARE_API_TOKEN npx -y wrangler@4 kv key put --remote --namespace-id "$NS_ID" "$VERSION_CODE/$lib" --path "$TMP_GZ" >/dev/null)
	done
	(cd telemetry/worker && env -u CLOUDFLARE_API_TOKEN npx -y wrangler@4 kv key put --remote --namespace-id "$NS_ID" "$VERSION_CODE/manifest.txt" --path "$DEST/manifest.txt" >/dev/null)
	rm -f "$TMP_GZ"
	echo "已上传 $VERSION_CODE 的符号到 KV"
fi
