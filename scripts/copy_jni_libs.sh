#!/usr/bin/env bash
# 复制预编译 Android arm64 动态库到 jniLibs
# 默认从仓库内置 third_party/android-native-libs 复制全部 112 个库:
#   Krita 核心库 + 笔刷插件 + Qt for Android + KF6 + NDK 依赖 + 预编译 libreverie_jni.so
# 克隆仓库后直接运行本脚本即可构建 APK, 无需本地 Qt/Krita 环境
#
# 用法:
#   scripts/copy_jni_libs.sh             预编译模式(默认), 复制全部含 libreverie_jni.so
#   scripts/copy_jni_libs.sh --no-jni    开发者模式(-PbuildNative), 跳过 libreverie_jni.so
#                                        避免与 CMake 重编译产物冲突
#   scripts/copy_jni_libs.sh <目录>      从自定义目录复制
set -e

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SKIP_JNI=0
SRC="$REPO_ROOT/third_party/android-native-libs"
if [ "$1" = "--no-jni" ]; then
	SKIP_JNI=1
	shift
fi
[ -n "$1" ] && SRC="$1"

if [ ! -d "$SRC" ] || ! ls "$SRC"/*.so >/dev/null 2>&1; then
	echo "未找到 $SRC"
	echo "请先获取依赖库: 克隆完整仓库 (third_party/android-native-libs)"
	exit 1
fi

DST="$REPO_ROOT/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$DST"
count=0
for f in "$SRC"/*.so; do
	b="$(basename "$f")"
	if [ "$SKIP_JNI" = "1" ] && [ "$b" = "libreverie_jni.so" ]; then
		rm -f "$DST/$b"
		continue
	fi
	cp -f "$f" "$DST/"
	count=$((count + 1))
done
echo "已复制 $count 个动态库到 $DST"
if [ "$SKIP_JNI" = "1" ]; then
	echo "已跳过 libreverie_jni.so (buildNative 模式由 CMake 重新编译)"
else
	echo "现在可以直接 ./gradlew assembleDebug 构建 (预编译 jni 模式)"
	echo "如需重新编译 C++: ./gradlew assembleDebug -PbuildNative (见 README 构建章节)"
fi
