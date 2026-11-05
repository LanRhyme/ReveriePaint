#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# 刷新 32 位 (armeabi-v7a) 预编译动态库闭包
#
# 与 arm64 的 scripts/refresh-prebuilt-jni.sh 对应, 但 32 位这套是「整包重收」:
# Krita/Qt/KF6 闭包 + ReverieCore 自己的两个 .so 一起落到
# third_party/android-native-libs-armeabi-v7a/。
#
# 为什么必须重收而不是单刷 jni:
#   闭包里的 Krita 库必须与编译 ReverieCore 时用的头文件同源, 否则拆库后
#   的 libreverie_jni.so/libreverie_core.so 会 NEEDED 到旧库上, 运行期
#   dlopen 或首次调用就挂。
#
# 2026-10-31 的教训 (1.6.2 32 位包启动即崩):
#   旧脚本只覆盖、不清理 DEST, 且某个源库缺失时只打一行"跳过"。结果是旧 .so
#   留在闭包里, 与这次重建的新库混在一起 (libkritaui 是新的、插件是旧的),
#   插件引用新版才有的符号, 闭包里没人定义 -> 启动 dlopen 失败。
#   现在改为: 先在 STAGE 收全 -> 缺任何一个源就整体失败 -> 再整体换入 DEST ->
#   最后跑 ABI 级符号门禁 (scripts/check_native_symbols.sh)。
#
# 前置:
#   - Krita 已有 armv7 构建目录 (默认 ~/Projects/krita-source/build-android-armv7)
#   - Qt for Android armv7 (默认 ~/Qt6/6.6.3/android_armv7)
#   - deps-armv7 KF6/mypaint 等依赖前缀
#   - ReverieCore 已用 scripts/build_native.sh --abi armeabi-v7a 编出产物
#
# 用法:
#   ./scripts/collect_armv7_libs.sh
#   KRITA_BIN=... QT_ARMV7=... ./scripts/collect_armv7_libs.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

DEST="${DEST:-$ROOT/third_party/android-native-libs-armeabi-v7a}"
KRITA_BIN="${KRITA_BIN:-$HOME/Projects/krita-source/build-android-armv7}"
DEPS="${DEPS:-$HOME/ReveriePaint-android/deps-armv7}"
QT_ARMV7="${QT_ARMV7:-$HOME/Qt6/6.6.3/android_armv7}"
NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/25.2.9519653}"
RV_BUILD="${RV_BUILD:-$HOME/rvbuild-armv7}"
REF_ARM64="${REF_ARM64:-$ROOT/third_party/android-native-libs}"

STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
if [ ! -x "$STRIP" ]; then
	echo "找不到 llvm-strip: $STRIP (设置 ANDROID_NDK_HOME 指向 NDK 25.2.9519653)" >&2
	exit 1
fi

for d in "$KRITA_BIN" "$QT_ARMV7" "$DEPS"; do
	if [ ! -d "$d" ]; then
		echo "缺少输入目录: $d (用 KRITA_BIN/QT_ARMV7/DEPS 环境变量覆盖默认路径)" >&2
		exit 1
	fi
done
if ! ls "$KRITA_BIN"/lib/*.so >/dev/null 2>&1; then
	echo "$KRITA_BIN/lib 下没有 .so, 先构建 Krita armv7" >&2
	exit 1
fi
if [ ! -f "$RV_BUILD/libreverie_jni.so" ] || [ ! -f "$RV_BUILD/libreverie_core.so" ]; then
	echo "缺少 ReverieCore 产物 ($RV_BUILD), 先跑 ./scripts/build_native.sh --abi armeabi-v7a" >&2
	exit 1
fi

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

strip_into() {
	local src="$1" name="$2"
	[ -f "$src" ] || { echo "缺少源库: $src" >&2; exit 1; }
	"$STRIP" --strip-unneeded "$src" -o "$STAGE/$name"
}

filter_libs() {
	case "$1" in
		libc++_shared.so | libc++_static.*) return 1 ;;
		*.a | *.cmake | *.o) return 1 ;;
	esac
	return 0
}

echo "== 1/5 筹备 Krita 闭包 (strip 到暂存目录) =="
n=0
for f in "$KRITA_BIN"/lib/*.so; do
	b="$(basename "$f")"
	[[ "$b" == lib* ]] || b="lib$b"
	filter_libs "$b" || continue
	# 只收 arm64 闭包里也有的同名库: 32 位保持与 64 位同一套模块,
	# 不把 exif/xmp/qml 这类可选模块 (及其外部依赖) 带进闭包。
	if [ ! -f "$REF_ARM64/$b" ]; then
		echo "   跳过 $b (arm64 闭包里没有同名库)"
		continue
	fi
	strip_into "$f" "$b"
	n=$((n + 1))
done
if [ "$n" -lt 40 ]; then
	echo "Krita 闭包只收到 $n 个库, 明显不完整 (构建目录没建完?)" >&2
	exit 1
fi
echo "   暂存 $n 个 Krita 库"

echo "== 2/5 KF6 与三方依赖 =="
n=0
for pat in "$DEPS"/lib/libKF6*.so \
	"$DEPS"/lib/libmypaint.so \
	"$DEPS"/lib/libjson-c.so \
	"$DEPS"/lib/libbz2.so \
	"$DEPS"/lib/libpng16.so \
	"$DEPS"/lib/libquazip1-qt6.so \
	"$DEPS"/lib/libintl.so \
	"$DEPS"/lib/libgsl.so \
	"$DEPS"/lib/libgslcblas.so \
	"$DEPS"/lib/liblzma.so \
	"$DEPS"/lib/libzstd.so \
	"$DEPS"/lib/libexpat.so \
	"$DEPS"/lib/libfreetype.so \
	"$DEPS"/lib/libunibreak.so \
	"$DEPS"/lib/libharfbuzz.so \
	"$DEPS"/lib/libfribidi.so \
	"$DEPS"/lib/libraqm.so; do
	if [ -f "$pat" ]; then
		strip_into "$pat" "$(basename "$pat")"
		n=$((n + 1))
		continue
	fi
	# 依赖只提供静态库时跳过: 对应符号已被静态链进 Krita/ReverieCore, 闭包里不需要 .so
	base="$(basename "$pat")"
	stem="${base%.so}"
	if [ -f "$DEPS/lib/$stem.a" ]; then
		echo "   跳过 $base (deps 只有 $stem.a, 已静态链接)"
		continue
	fi
	# 既没有 .so 也没有 .a: 该依赖可能根本没有构建 (旧闭包里也没有)。
	# 不在这里硬失败, 交给末尾的 ABI 符号门禁判定"到底有没有人需要它"。
	echo "   警告: $base 在 deps 里既没有 .so 也没有 $stem.a, 跳过 (由符号门禁兜底)" >&2
	continue
done
echo "   暂存 $n 个依赖库"

echo "== 3/5 Qt 闭包 (必须与 arm64 同名模块一一对应) =="
n=0
miss=0
for f in "$REF_ARM64"/libQt6*.so "$REF_ARM64"/libQtAndroid*.so; do
	[ -f "$f" ] || continue
	b="$(basename "$f")"
	armv7_name="${b//arm64-v8a/armeabi-v7a}"
	if [ -f "$QT_ARMV7/lib/$armv7_name" ]; then
		strip_into "$QT_ARMV7/lib/$armv7_name" "$armv7_name"
		n=$((n + 1))
	else
		echo "   缺少 Qt armv7 库: $armv7_name (arm64 闭包里有, 32 位就会缺模块)" >&2
		miss=$((miss + 1))
	fi
done
if [ "$miss" -ne 0 ]; then
	echo "Qt 闭包缺 $miss 个模块, 拒绝生成半套闭包" >&2
	exit 1
fi
echo "   暂存 $n 个 Qt 库"

echo "== 4/5 libc++_shared 与 ReverieCore =="
strip_into "$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/arm-linux-androideabi/libc++_shared.so" libc++_shared.so
strip_into "$RV_BUILD/libreverie_jni.so" libreverie_jni.so
strip_into "$RV_BUILD/libreverie_core.so" libreverie_core.so

echo "== 5/5 整体换入闭包并做 ABI 符号门禁 =="
mkdir -p "$DEST"
# 先清空旧的 .so, 避免源库缺失时留下上一轮的库 (1.6.2 的事故形态)
find "$DEST" -maxdepth 1 -name '*.so' -delete
cp -f "$STAGE"/*.so "$DEST"/
echo "   $(ls -1 "$DEST"/*.so | wc -l) 个库已换入 $DEST"

"$ROOT/scripts/check_native_symbols.sh" armeabi-v7a
echo "下一步: ./scripts/check_jni_symbols.sh armeabi-v7a"
