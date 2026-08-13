#!/usr/bin/env bash
# 复制预编译 Android arm64 动态库到 jniLibs
# 默认从仓库内置 third_party/android-native-libs 复制全部 112 个库:
#   Krita 核心库 + 笔刷插件 + Qt for Android + KF6 + NDK 依赖 + 预编译 libreverie_jni.so
# 克隆仓库后直接运行本脚本即可构建 APK, 无需本地 Qt/Krita 环境
# 用法: scripts/copy_jni_libs.sh [自定义库目录]
set -e

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${1:-$REPO_ROOT/third_party/android-native-libs}"
DST="$REPO_ROOT/app/src/main/jniLibs/arm64-v8a"

if [ ! -d "$SRC" ] || ! ls "$SRC"/*.so >/dev/null 2>&1; then
	echo "未找到 $SRC"
	echo "请先获取依赖库: 克隆完整仓库 (third_party/android-native-libs) 或运行 scripts/fetch_native_libs.sh"
	exit 1
fi

mkdir -p "$DST"
cp -f "$SRC"/*.so "$DST/"
echo "已复制 $(ls "$SRC"/*.so | wc -l) 个动态库到 $DST"
echo "现在可以直接 ./gradlew assembleDebug 构建 (预编译 jni 模式)"
echo "如需重新编译 C++: ./gradlew assembleDebug -PbuildNative (见 README 构建章节)"
