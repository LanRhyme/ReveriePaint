#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""从 KDE 的 Krita Android 依赖包仓库提取第三方库**头文件**。

背景: Krita 的 CMake 依赖 KDE 以 GitLab generic package 形式发布的预编译 Android
依赖 (项目 ``teams/ci-artifacts/krita-android-arm64-v8a``)。本仓库只需要这些包的
**头文件** —— 运行时/链接用的 .so 已经在 ``third_party/`` 里, 因此脚本只解出
``include/`` 目录并合并进 ``DEPS_DIR/include``, 不下载完整构建、也不编译任何东西。

注意: 不取 ``ext_qt`` / ``ext_k*`` (Qt 与 KF6 另有来源: Qt 用官方 6.6.3, KF6 用
6.6.0 源码合成), 避免把新版本头文件混进来造成 ABI 不一致。

用法::

    fetch_kde_dep_headers.py --deps-dir DIR [--packages a b c] [--list]
"""

from __future__ import annotations

import argparse
import io
import json
import os
import re
import sys
import tarfile
import urllib.request
from pathlib import Path

PROJECT_ID = 17426  # teams/ci-artifacts/krita-android-arm64-v8a
API_TEMPLATE = "https://invent.kde.org/api/v4/projects/{pid}/packages?per_page=100&page={page}"
DOWNLOAD_TEMPLATE = (
    "https://invent.kde.org/api/v4/projects/{pid}/packages/generic/{name}/{version}/archive.tar"
)

# Krita 头文件会传递引入的第三方依赖 (按需增补; 缺失会在编译期以 fatal error 暴露)
DEFAULT_PACKAGES = [
    "ext_boost",
    "ext_lcms2",
    "ext_exiv2",
    "ext_openexr",
    "ext_imath",
    "ext_fftw3",
    "ext_quazip",
    "ext_png",
    "ext_tiff",
    "ext_gsl",
    "ext_xsimd",
    "ext_highway",
    "ext_immer",
    "ext_lager",
    "ext_zug",
    "ext_brotli",
    "ext_jpegxl",
    "ext_icu",
    "ext_mypaint",
    "ext_seexpr",
    "ext_openjpeg",
    "ext_webp",
    "ext_giflib",
    "ext_json_c",
    "ext_fribidi",
    "ext_expat",
    "ext_jpeg",
    "ext_libintl-lite",
    "ext_lzma",
]

# 只供链接期解析 -l 用 (--as-needed 下不会进 NEEDED, 无需进 APK)
DEFAULT_LIB_PACKAGES = ["ext_expat"]

TIMESTAMP_RE = re.compile(r"(\d{9,})$")

# 带版本号的安装子目录 (KDE 的 CMake 会把它们各自加进 include 路径, 而
# ReveriePaint 的 CMakeLists 只加了 ${DEPS_DIR}/include 根目录, 所以需要扁平化)
VERSIONED_DIR_RE = re.compile(r"^(?=.*\d)[A-Za-z][A-Za-z0-9_.+-]*$")
# 这类目录名不带数字, 但 Krita 头文件是裸 include (如 <half.h>), 也要扁平化
EXTRA_FLATTEN = {"Imath", "OpenEXR"}
NEVER_FLATTEN = {"KF6", "libunibreak", "freetype2", "eigen3", "gsl", "immer", "lager"}


def log(msg: str) -> None:
    print(f"[fetch-kde-headers] {msg}")


def api_get(url: str):
    with urllib.request.urlopen(url, timeout=60) as response:
        return json.load(response)


def list_packages() -> dict[str, list[str]]:
    """返回 {包名: [版本...]}。"""
    out: dict[str, list[str]] = {}
    page = 1
    while True:
        data = api_get(API_TEMPLATE.format(pid=PROJECT_ID, page=page))
        if not data:
            break
        for item in data:
            out.setdefault(item["name"], []).append(item["version"])
        page += 1
        if page > 10:
            break
    return out


def pick_version(versions: list[str]) -> str:
    """优先 master-*, 否则取时间戳最大的。"""
    masters = [v for v in versions if v.startswith("master-")]
    pool = masters or versions
    return sorted(pool, key=lambda v: TIMESTAMP_RE.search(v).group(1) if TIMESTAMP_RE.search(v) else v)[-1]


def extract_includes(archive_bytes: bytes, include_dir: Path) -> int:
    """只把 archive 里 include/ 下的文件解到 include_dir。"""
    count = 0
    with tarfile.open(fileobj=io.BytesIO(archive_bytes)) as tar:
        for member in tar.getmembers():
            if not member.isfile():
                continue
            parts = Path(member.name).parts
            if not parts or parts[0] != "include" or len(parts) < 2:
                continue
            rel = Path(*parts[1:])
            target = include_dir / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            extracted = tar.extractfile(member)
            if extracted is not None:
                target.write_bytes(extracted.read())
                count += 1
    return count


def extract_libs(archive_bytes: bytes, lib_dir: Path) -> int:
    """把 archive 里 lib/ 下的 .so 提取到 lib_dir (供链接期 -l 解析)。"""
    lib_dir.mkdir(parents=True, exist_ok=True)
    real_files: dict[str, bytes] = {}
    with tarfile.open(fileobj=io.BytesIO(archive_bytes)) as tar:
        for member in tar.getmembers():
            if not member.isfile():
                continue
            parts = Path(member.name).parts
            if len(parts) < 2 or parts[0] != "lib":
                continue
            name = parts[-1]
            if ".so" not in name:
                continue
            handle = tar.extractfile(member)
            if handle is not None:
                real_files[name] = handle.read()
    count = 0
    for name, data in real_files.items():
        (lib_dir / name).write_bytes(data)
        count += 1
    # 保证存在不带版本号的 .so (链接器按 -lxxx 查找的就是它)
    for name in list(real_files):
        if ".so." not in name:
            continue
        plain = name.split(".so.")[0] + ".so"
        if plain not in real_files and not (lib_dir / plain).exists():
            (lib_dir / plain).write_bytes(real_files[name])
            count += 1
    return count


def flatten_versioned_dirs(include_dir: Path) -> int:
    """把 include/<带版本号的目录>/ 的内容软链到 include/ 一级, 让裸 include 能用。

    例: ``include/boost-1_90/boost`` -> ``include/boost``;
        ``include/libpng16/png.h``  -> ``include/png.h``。
    """
    count = 0
    for entry in sorted(include_dir.iterdir()):
        if not entry.is_dir() or entry.name in NEVER_FLATTEN:
            continue
        if not VERSIONED_DIR_RE.match(entry.name) and entry.name not in EXTRA_FLATTEN:
            continue
        for child in sorted(entry.iterdir()):
            link = include_dir / child.name
            if link.exists() or link.is_symlink():
                continue
            os.symlink(child, link)
            count += 1
    return count


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--deps-dir", required=True, help="DEPS_DIR (头文件解到 <deps-dir>/include)")
    parser.add_argument("--packages", nargs="*", default=DEFAULT_PACKAGES)
    parser.add_argument("--list", action="store_true", help="只列出仓库里的包名与版本")
    parser.add_argument("--flatten-only", action="store_true", help="只做版本化目录扁平化, 不下载")
    parser.add_argument(
        "--libs-for",
        nargs="*",
        default=DEFAULT_LIB_PACKAGES,
        help="额外提取这些包的 lib/*.so 到 <deps-dir>/lib (仅链接期需要)",
    )
    args = parser.parse_args()

    include_dir = Path(args.deps_dir).resolve() / "include"

    if args.flatten_only:
        log(f"扁平化 {include_dir} 下的版本化目录 ...")
        log(f"新增软链 {flatten_versioned_dirs(include_dir)} 个")
        return 0

    available = list_packages()
    if args.list:
        for name in sorted(available):
            log(f"{name}: {', '.join(sorted(available[name]))}")
        return 0

    include_dir.mkdir(parents=True, exist_ok=True)

    for name in args.packages:
        versions = available.get(name)
        if not versions:
            log(f"跳过 {name}: 仓库中不存在")
            continue
        version = pick_version(versions)
        url = DOWNLOAD_TEMPLATE.format(pid=PROJECT_ID, name=name, version=version)
        log(f"下载 {name} {version} ...")
        try:
            with urllib.request.urlopen(url, timeout=600) as response:
                data = response.read()
        except Exception as exc:  # noqa: BLE001 - 单个包失败不应中断整体流程
            log(f"失败 {name}: {exc}")
            continue
        written = extract_includes(data, include_dir)
        log(f"  {name}: 解出 {written} 个头文件")

    lib_dir = Path(args.deps_dir).resolve() / "lib"
    for name in args.libs_for or []:
        versions = available.get(name)
        if not versions:
            log(f"跳过 {name} (lib): 仓库中不存在")
            continue
        version = pick_version(versions)
        url = DOWNLOAD_TEMPLATE.format(pid=PROJECT_ID, name=name, version=version)
        log(f"下载 {name} {version} (lib) ...")
        try:
            with urllib.request.urlopen(url, timeout=600) as response:
                data = response.read()
        except Exception as exc:  # noqa: BLE001
            log(f"失败 {name} (lib): {exc}")
            continue
        log(f"  {name}: 解出 {extract_libs(data, lib_dir)} 个库文件到 {lib_dir}")

    log(f"扁平化版本化目录: 新增软链 {flatten_versioned_dirs(include_dir)} 个")
    log(f"头文件合并到 {include_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
