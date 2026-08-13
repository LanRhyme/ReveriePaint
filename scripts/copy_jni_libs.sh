#!/usr/bin/env bash
# 拷贝 Krita Android arm64 动态库到 jniLibs
# 优先使用本地交叉编译产物, 否则从 GitHub Release 下载预编译包(免编译)
# 用法: scripts/copy_jni_libs.sh [krita-build-lib-dir]
set -e
SRC="${1:-$HOME/Projects/krita-source/build-android/lib}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DST="$REPO_ROOT/app/src/main/jniLibs/arm64-v8a"
RELEASE_URL="https://github.com/LanRhyme/ReveriePaint/releases/download/v1.0.0-prebuilt-libs/krita-android-libs-arm64-v8a.tar.gz"
mkdir -p "$DST"

if [ -d "$SRC" ] && ls "$SRC"/lib*.so >/dev/null 2>&1; then
    for f in "$SRC"/lib*.so; do cp -f "$f" "$DST/"; done
    for f in "$SRC"/krita*paintop.so; do
        [ -f "$f" ] && cp -f "$f" "$DST/lib$(basename "$f")"
    done
    echo "copied $(ls "$SRC"/lib*.so | wc -l) Krita libs + paintop plugins from $SRC"
else
    echo "本地 Krita 构建不存在 ($SRC), 从 GitHub Release 下载预编译库..."
    TMP="$(mktemp -d)"
    curl -fL --retry 3 -o "$TMP/libs.tar.gz" "$RELEASE_URL"
    tar -xzf "$TMP/libs.tar.gz" -C "$TMP"
    cp -f "$TMP"/lib/*.so "$DST/"
    cp -f "$TMP"/lib-prefixed/*.so "$DST/"
    rm -rf "$TMP"
    echo "downloaded prebuilt Krita libs to $DST"
fi
