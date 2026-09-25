#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# 在 Linux/WSL 宿主机上准备 buildNative 所需的依赖环境。
#
# 为什么需要本脚本: 仓库只提交预编译 .so (third_party/), 不带 Krita/Qt/KF6 的头文件;
# 而 CMakeLists.txt 的 NDK 路径按 Linux 宿主写死 (prebuilt/linux-x86_64), 所以
# C++ 重编译必须在 Linux 环境里做。本脚本组装四类依赖:
#
#   QT_ANDROID_DIR  官方 Qt for Android 6.6.3 (android_arm64_v8a)
#   QT_HOST_DIR     宿主 Qt 6.6.3 (KF6 配置时需要 moc/rcc/uic)
#   NDK_DIR         Android NDK r25c (与预编译库同源: clang 14.0.7)
#   KRITA_SRC_DIR   Krita 源码 (6.1.0-prealpha, 与预编译库同版本)
#   KRITA_BIN_DIR   本脚本按源码合成 (config/export 头 + 库软链)
#   DEPS_DIR        本脚本按 KF6 6.6.0 源码合成 (头文件前缀 + 库软链)
#
# 首次使用:
#   scripts/setup_native_env.sh --fetch      # 下载缺失的 Qt/KF6/第三方头文件
#   scripts/build_native_wsl.sh              # 交叉编译 libreverie_jni.so
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_ROOT="${REVERIE_ENV_ROOT:-$HOME/reverie-deps}"

QT_ANDROID_DIR="${QT_ANDROID_DIR:-$ENV_ROOT/Qt/6.6.3/android_arm64_v8a}"
QT_HOST_DIR="${QT_HOST_DIR:-$ENV_ROOT/QtHost/6.6.3/gcc_64}"
NDK_DIR="${NDK_DIR:-$ENV_ROOT/android-ndk-r25c}"
KRITA_SRC_DIR="${KRITA_SRC_DIR:-$ENV_ROOT/krita-source}"
KRITA_BIN_DIR="${KRITA_BIN_DIR:-$ENV_ROOT/krita-build}"
DEPS_DIR="${DEPS_DIR:-$ENV_ROOT/deps}"
KF6_SRC_ROOT="${KF6_SRC_ROOT:-$ENV_ROOT/src}"
HEADERS_SRC="${HEADERS_SRC:-$ENV_ROOT/headers}"
AQT_VENV="${AQT_VENV:-$HOME/aqtenv}"

QT_VERSION="6.6.3"
KF6_VERSION="6.6.0"
NDK_VERSION="r25c"
KRITA_BRANCH="master"
KF6_MODULES="extra-cmake-modules karchive kcodecs kcolorscheme kcompletion kconfig kcoreaddons kguiaddons ki18n kitemviews kwidgetsaddons"

die() { echo "错误: $*" >&2; exit 1; }
info() { echo "[setup-native-env] $*"; }
have() { command -v "$1" >/dev/null 2>&1; }

# 从 KDE 的 Krita Android 依赖包 (GitLab generic package) 提取指定子目录到目标位置
# 用法: fetch_kde_package <包名> <版本> <包内子路径> <目标目录>
fetch_kde_package() {
	local name="$1" version="$2" subpath="$3" dest="$4"
	local tmp="$ENV_ROOT/.pkg-$name"
	rm -rf "$tmp"
	mkdir -p "$tmp"
	local url="https://invent.kde.org/api/v4/projects/17426/packages/generic/$name/$version/archive.tar"
	if ! curl -sL --retry 3 -o "$tmp/archive.tar" "$url"; then
		echo "错误: 下载 $name 失败 ($url)" >&2
		return 1
	fi
	tar xf "$tmp/archive.tar" -C "$tmp"
	mkdir -p "$dest"
	cp -r "$tmp/$subpath/." "$dest/"
	rm -rf "$tmp"
}

fetch_all=0
if [ "${1:-}" = "--fetch" ]; then
	fetch_all=1
fi

# ---------------------------------------------------------------- 工具校验

have cmake || die "缺少 cmake (apt install cmake)"
have ninja || die "缺少 ninja (apt install ninja-build)"
have python3 || die "缺少 python3"
have curl || die "缺少 curl"

if [ "$fetch_all" = "1" ]; then
	mkdir -p "$ENV_ROOT"/{src,logs,headers}

	# --- Qt for Android + 宿主 Qt (aqtinstall) ---
	if [ ! -d "$QT_ANDROID_DIR" ] || [ ! -d "$QT_HOST_DIR" ]; then
		info "安装 Qt $QT_VERSION (Android arm64 + 宿主 gcc_64) ..."
		[ -d "$AQT_VENV" ] || python3 -m venv "$AQT_VENV"
		"$AQT_VENV/bin/pip" install -q --upgrade pip aqtinstall
		[ -d "$QT_ANDROID_DIR" ] || "$AQT_VENV/bin/aqt" install-qt linux android "$QT_VERSION" android_arm64_v8a -O "$ENV_ROOT/Qt"
		[ -d "$QT_HOST_DIR" ] || "$AQT_VENV/bin/aqt" install-qt linux desktop "$QT_VERSION" gcc_64 --archives qtbase -O "$ENV_ROOT/QtHost"
	fi

	# --- Android NDK r25c (clang 14.0.7, 与预编译库同源) ---
	if [ ! -d "$NDK_DIR" ]; then
		info "下载 Android NDK $NDK_VERSION ..."
		curl -L -C - --retry 3 -o "$ENV_ROOT/android-ndk-$NDK_VERSION-linux.zip" \
			"https://dl.google.com/android/repository/android-ndk-$NDK_VERSION-linux.zip"
		unzip -q -o "$ENV_ROOT/android-ndk-$NDK_VERSION-linux.zip" -d "$ENV_ROOT"
	fi

	# --- Krita 源码 (6.1.0-prealpha, 归档 tar 包比 git clone 稳) ---
	if [ ! -d "$KRITA_SRC_DIR" ]; then
		info "下载 Krita 源码 ($KRITA_BRANCH) ..."
		curl -L --retry 3 -o "$ENV_ROOT/krita-$KRITA_BRANCH.tar.gz" \
			"https://invent.kde.org/graphics/krita/-/archive/$KRITA_BRANCH/krita-$KRITA_BRANCH.tar.gz"
		mkdir -p "$KRITA_SRC_DIR"
		tar xf "$ENV_ROOT/krita-$KRITA_BRANCH.tar.gz" -C "$KRITA_SRC_DIR" --strip-components=1
	fi

	# --- KF6 模块源码 ---
	for module in $KF6_MODULES; do
		tarball="$KF6_SRC_ROOT/$module-$KF6_VERSION.tar.xz"
		[ -f "$tarball" ] && continue
		info "下载 KF6 模块 $module-$KF6_VERSION ..."
		curl -sL -o "$tarball" "https://download.kde.org/stable/frameworks/6.6/$module-$KF6_VERSION.tar.xz"
	done
	for archive in "$KF6_SRC_ROOT"/*.tar.xz; do
		[ -f "$archive" ] || continue
		dir="${archive%.tar.xz}"
		[ -d "$dir" ] || tar xf "$archive" -C "$KF6_SRC_ROOT"
	done

	# --- 第三方头文件 (与 Qt 版本无关, 取上游发行包即可) ---
	if [ ! -d "$HEADERS_SRC/eigen3" ]; then
		info "下载 Eigen 3.4.0 头文件 ..."
		curl -sL -o "$ENV_ROOT/eigen.tar.gz" "https://gitlab.com/libeigen/eigen/-/archive/3.4.0/eigen-3.4.0.tar.gz"
		mkdir -p "$HEADERS_SRC/eigen3"
		rm -rf "$ENV_ROOT/eigen-tmp"; mkdir -p "$ENV_ROOT/eigen-tmp"
		tar xf "$ENV_ROOT/eigen.tar.gz" -C "$ENV_ROOT/eigen-tmp" --strip-components=1
		cp -r "$ENV_ROOT/eigen-tmp/Eigen" "$HEADERS_SRC/eigen3/"
	fi
	# freetype / libunibreak: 上游发行包 (savannah / GitHub) 在部分网络不可达,
	# 改从 KDE 的 Android 依赖包提取头文件 (与本项目 .so 同源, 更可靠)
	if [ ! -d "$HEADERS_SRC/freetype2" ]; then
		info "从 KDE 包提取 FreeType 头文件 ..."
		fetch_kde_package ext_freetype transition.now-qt6-1747405499 include/freetype2 "$HEADERS_SRC/freetype2"
	fi
	if [ ! -d "$HEADERS_SRC/libunibreak" ]; then
		info "从 KDE 包提取 libunibreak 头文件 ..."
		fetch_kde_package ext_unibreak transition.now-qt6-1747405149 include "$HEADERS_SRC/libunibreak"
	fi
fi

# ---------------------------------------------------------------- 前置校验

[ -d "$QT_ANDROID_DIR" ] || die "缺少 Qt for Android: $QT_ANDROID_DIR (先跑 --fetch)"
[ -d "$NDK_DIR" ] || die "缺少 Android NDK: $NDK_DIR (先跑 --fetch)"
[ -d "$KRITA_SRC_DIR" ] || die "缺少 Krita 源码: $KRITA_SRC_DIR (先跑 --fetch)"
for module in kconfig kcoreaddons ki18n; do
	[ -d "$KF6_SRC_ROOT/$module-$KF6_VERSION" ] || die "缺少 KF6 源码: $KF6_SRC_ROOT/$module-$KF6_VERSION (先跑 --fetch)"
done

# ---------------------------------------------------------------- 合成环境

# ---------------------------------------------------------------- 静态 expat

# CMakeLists 链接 -lexpat, 但引擎并不引用 expat 符号; 仓库发布的预编译库里也没有
# libexpat.so (NEEDED 中不存在该项)。若用动态 expat, 产物会多出一条运行时依赖而
# APK 里没有对应 .so, 真机会加载失败 —— 因此交叉编译静态库, 符号直接并入。
ensure_expat_link_stub() {
	local lib_dir="$DEPS_DIR/lib"
	# 动态 expat 会引入 NEEDED[libexpat.so], 而 APK 里没有这个库 -> 真机加载失败, 必须移除
	rm -f "$lib_dir/libexpat.so"
	if [ -f "$lib_dir/libexpat.a" ]; then
		return 0
	fi
	mkdir -p "$lib_dir"
	local ar="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar"
	if [ ! -x "$ar" ]; then
		echo "错误: 未找到 llvm-ar: $ar" >&2
		return 1
	fi
	"$ar" rcs "$lib_dir/libexpat.a"
	echo "[setup-native-env] 生成空 libexpat.a 占位 (仅满足 -lexpat 查找, 不引入运行时依赖)"
}

ensure_expat_link_stub

# 第三方头文件 (boost/lcms2/openexr/... ) 来自 KDE 的 Android 依赖包:
# 仓库自己的 CMakeLists 只把 ${DEPS_DIR}/include 加进搜索路径, 所以这些包解出的
# include/ 需要合并到同一处, 并把带版本号的子目录扁平化 (如 boost-1_90 -> boost)
if ! ls "$DEPS_DIR"/include/boost-* >/dev/null 2>&1; then
	echo "[setup-native-env] 提取 KDE 依赖包头文件 ..."
	python3 "$REPO_ROOT/scripts/fetch_kde_dep_headers.py" --deps-dir "$DEPS_DIR"
else
	python3 "$REPO_ROOT/scripts/fetch_kde_dep_headers.py" --deps-dir "$DEPS_DIR" --flatten-only
fi

python3 "$REPO_ROOT/scripts/prepare_native_deps.py" \
	--repo "$REPO_ROOT" \
	--krita-src "$KRITA_SRC_DIR" \
	--kf6-src "$KF6_SRC_ROOT" \
	--krita-bin-dir "$KRITA_BIN_DIR" \
	--deps-dir "$DEPS_DIR" \
	--headers-src "$HEADERS_SRC"

cat >"$ENV_ROOT/native-env.sh" <<EOF
# 由 scripts/setup_native_env.sh 生成, 供 scripts/build_native_wsl.sh 使用
export REPO_ROOT="$REPO_ROOT"
export QT_ANDROID_DIR="$QT_ANDROID_DIR"
export QT_HOST_DIR="$QT_HOST_DIR"
export NDK_DIR="$NDK_DIR"
export KRITA_SRC_DIR="$KRITA_SRC_DIR"
export KRITA_BIN_DIR="$KRITA_BIN_DIR"
export DEPS_DIR="$DEPS_DIR"
EOF

info "环境变量已写入 $ENV_ROOT/native-env.sh"
info "下一步: scripts/build_native_wsl.sh"
