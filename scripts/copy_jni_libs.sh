#!/usr/bin/env bash
# 拷贝 Krita Android arm64 动态库到 jniLibs
# 优先级: 本地交叉编译产物 > 仓库内置 third_party 预编译库
# 用法: scripts/copy_jni_libs.sh [krita-build-lib-dir]
set -e
SRC="${1:-$HOME/Projects/krita-source/build-android/lib}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DST="$REPO_ROOT/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$DST"

copy_libs() {
	local dir="$1"
	for f in "$dir"/lib*.so; do cp -f "$f" "$DST/"; done
	for f in "$dir"/krita*paintop.so; do
		[ -f "$f" ] && cp -f "$f" "$DST/lib$(basename "$f")"
	done
	for f in "$dir"/lib-prefixed/*.so; do
		[ -f "$f" ] && cp -f "$f" "$DST/"
	done
}

if [ -d "$SRC" ] && ls "$SRC"/lib*.so >/dev/null 2>&1; then
	copy_libs "$SRC"
	echo "copied Krita libs + paintop plugins from local build $SRC"
elif [ -d "$REPO_ROOT/third_party/krita-android-libs" ] && ls "$REPO_ROOT/third_party/krita-android-libs"/lib*.so >/dev/null 2>&1; then
	copy_libs "$REPO_ROOT/third_party/krita-android-libs"
	echo "copied Krita libs from third_party/krita-android-libs (仓库内置预编译库)"
else
	echo "未找到 Krita 库, 请先交叉编译或从 GitHub Release 下载解压到 third_party/krita-android-libs/"
	exit 1
fi
