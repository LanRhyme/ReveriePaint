#!/usr/bin/env bash
# 从本地 Krita Android 构建目录拷贝运行时 .so 到 jniLibs
# 用法: scripts/copy_jni_libs.sh
set -e
SRC="${1:-$HOME/Projects/krita-source/build-android/lib}"
DST="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$DST"
for f in "$SRC"/lib*.so; do cp -f "$f" "$DST/"; done
echo "copied $(ls "$SRC"/lib*.so | wc -l) libs to $DST"
