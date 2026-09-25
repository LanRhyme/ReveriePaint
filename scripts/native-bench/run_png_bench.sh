#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# 在 WSL/Linux 宿主上编译并运行 PNG 压缩档位基准 (见同目录 .cpp 说明)。
# 需要 ~/reverie-deps/native-env.sh 提供的 QT_HOST_DIR (setup_native_env.sh 生成)。
#
# 注意: 官方 Qt linux 二进制依赖 ICU 56, 而较新的发行版 (Debian 13 = ICU 76) 已无该
# soname。本脚本生成一组"空壳"ICU 56 库让动态链接器能解析符号 —— 基准只用到
# QImage/QPainter/PNG 编码, 不触碰 ICU 路径, 因此桩函数不会被调用。若基准因 ICU
# 崩溃, 说明该假设不成立, 此脚本的输出不可用。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_ROOT="${REVERIE_ENV_ROOT:-$HOME/reverie-deps}"

if [ -f "$ENV_ROOT/native-env.sh" ]; then
	# shellcheck disable=SC1091
	. "$ENV_ROOT/native-env.sh"
fi

QT="${QT_HOST_DIR:?未找到 QT_HOST_DIR, 请先运行 scripts/setup_native_env.sh}"
WORK="${TMPDIR:-/tmp}/pngbench"
rm -rf "$WORK"
mkdir -p "$WORK/icu56"
OUT="$WORK/pngbench"

# --- 1) 生成 ICU 56 桩库 ---
{
	echo '/* 自动生成: ICU 56 空壳符号, 仅供宿主基准链接 */'
	for so in "$QT/lib/libQt6Core.so" "$QT/lib/libQt6Gui.so"; do
		[ -f "$so" ] || continue
		nm -D --undefined-only "$so" 2>/dev/null | awk '{print $NF}'
	done | grep '_56$' | sort -u | while read -r sym; do
		echo "void *${sym}(void) { return 0; }"
	done
} > "$WORK/icu56/stubs.c"

gcc -shared -fPIC -o "$WORK/icu56/libicuuc.so.56" "$WORK/icu56/stubs.c"
for name in libicui18n.so.56 libicudata.so.56; do
	ln -sf libicuuc.so.56 "$WORK/icu56/$name"
done
echo "[png-bench] ICU 56 桩符号数: $(grep -c 'void \*' "$WORK/icu56/stubs.c")"

# --- 2) 编译基准 ---
# 链接期也要让 ld 找到 Qt6Core 的 ICU 56 依赖 (DT_NEEDED), 仅 -L 不够
LD_LIBRARY_PATH="$WORK/icu56:$QT/lib" \
	g++ -O2 -fPIC -o "$OUT" "$REPO_ROOT/scripts/native-bench/png_compression_bench.cpp" \
	-I"$QT/include" -I"$QT/include/QtCore" -I"$QT/include/QtGui" \
	-L"$WORK/icu56" -L"$QT/lib" -lQt6Gui -lQt6Core

# --- 3) 运行 ---
LD_LIBRARY_PATH="$WORK/icu56:$QT/lib" "$OUT"
