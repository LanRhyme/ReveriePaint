#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""合成 buildNative (Linux/WSL 宿主 → Android arm64) 所需的头文件环境。

背景: 本仓库只提交预编译 .so (third_party/), 不含 Krita/KF6 的头文件。本脚本
**不运行** Krita/KF6 的 CMake configure (那需要完整 deps 与工具链), 而是直接合成
编译期需要的东西:

1. 解析 Krita/KF6 的 ``generate_export_header()`` / ``ecm_generate_export_header()``
   调用, 合成对应的 ``*_export.h`` (宏置空, 可见性走默认值);
2. 用迷你 configure 处理 ``*.h.cmake`` 模板 (``config-*.h`` / ``KoConfig.h`` /
   ``kritaversion.h`` 等), 取值与预编译库一致;
3. 把 KF6 6.6.0 源码头文件组织成 ``DEPS_DIR/include/KF6/<Module>`` 布局;
4. 用软链把仓库里已有的预编译 .so/.a 接到 ``KRITA_BIN_DIR/lib`` 与 ``DEPS_DIR/lib``。

CMakeLists 里所有 ``${KRITA_BIN_DIR}/...`` / ``${DEPS_DIR}/...`` 的 include 目录都
指向本脚本生成的目录, 因此编译我们的 JNI 不再需要 Krita 的构建目录。

用法::

    prepare_native_deps.py --repo DIR --krita-src DIR --kf6-src DIR \
                           --krita-bin-dir DIR --deps-dir DIR
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import sys
from pathlib import Path

# ---------------------------------------------------------------- 常量

# KF6 模块 -> 安装到 include/KF6 下的子目录 (与 CMakeLists 的 include 列表一致)
KF6_MODULE_DIRS = {
    "karchive": ["KArchive"],
    "kcodecs": ["KCodecs"],
    "kcolorscheme": ["KColorScheme"],
    "kcompletion": ["KCompletion"],
    # KConfig 拆成 core/gui 两个库, 头文件目录也拆开; 为省事三个目录都放一份
    "kconfig": ["KConfig", "KConfigCore", "KConfigGui"],
    "kcoreaddons": ["KCoreAddons"],
    "kguiaddons": ["KGuiAddons"],
    "ki18n": ["KI18n"],
    "kitemviews": ["KItemViews"],
    "kwidgetsaddons": ["KWidgetsAddons"],
}

# 合成 config 头时的取值: None = 不定义 (等价于 CMake 的 OFF/未找到)
KRITA_CONFIG_VALUES = {
    "KRITA_VERSION_STRING": "6.1.0-prealpha",
    "KRITA_STABLE_VERSION_MAJOR": "6",
    "KRITA_STABLE_VERSION_MINOR": "1",
    "KRITA_VERSION_RELEASE": "0",
    "KRITA_ALPHA": "1",
    "WORDS_BIGENDIAN": None,
    "USE_DRMINGW": None,
    "HAVE_OPENEXR": "1",
    "HAVE_LCMS2": "1",
    "HAVE_LCMS24": "1",
    "HAVE_LCMS_FAST_FLOAT_PLUGIN": "1",
    "HAVE_DBUS": None,
    "HAVE_KCRASH": None,
    "HAVE_X11": None,
    "HAVE_WAYLAND": None,
    # 预编译库是 release 构建: 断言隐藏、锁自由哈希表开启
    "USE_LOCK_FREE_HASH_TABLE": "1",
    "HIDE_SAFE_ASSERTS": "1",
    "CRASH_ON_SAFE_ASSERTS": None,
    "SAFE_ASSERTS_ARE_ENABLED": None,
}

EXPORT_HEADER_RE = re.compile(
    r"(?m)(?:ecm_)?generate_export_header\s*\(\s*([A-Za-z0-9_:]+)([^)]*)\)"
)
CMAKE_DEFINE_RE = re.compile(r"(?m)^\s*#cmakedefine01\s+([A-Za-z0-9_]+)\s*$")
CMAKE_DEFINE_VAL_RE = re.compile(
    r"(?m)^(\s*)#cmakedefine\s+([A-Za-z0-9_]+)(?:\s+([^\n]*))?$"
)
CMAKE_VAR_RE = re.compile(r"@([A-Za-z0-9_]+)@")

# CMake GenerateExportHeader 的默认导出/弃用宏名后缀
EXPORT_SUFFIXES = (
    ("_EXPORT", "export"),
    ("_NO_EXPORT", "no_export"),
    ("_DEPRECATED", "deprecated"),
    ("_DEPRECATED_EXPORT", "deprecated_export"),
    ("_DEPRECATED_NO_EXPORT", "deprecated_no_export"),
)


def log(msg: str) -> None:
    print(f"[prepare-native-deps] {msg}")


def die(msg: str) -> None:
    print(f"[prepare-native-deps] 错误: {msg}", file=sys.stderr)
    sys.exit(1)


# ---------------------------------------------------------------- export 头合成


def _parse_export_args(args: str) -> dict:
    """把 ``BASE_NAME x EXPORT_MACRO_NAME Y`` 解析成 dict。"""
    out: dict[str, str] = {}
    tokens = args.split()
    i = 0
    while i + 1 < len(tokens):
        out[tokens[i].upper()] = tokens[i + 1]
        i += 2
    return out


def _export_guard(filename: str) -> str:
    return re.sub(r"[^A-Za-z0-9]", "_", filename).upper() + "_H"


def _export_header_text(macro: str, guard: str, no_export: str) -> str:
    prefix = macro.rsplit("_EXPORT", 1)[0]
    lines = [
        "/* 由 scripts/prepare_native_deps.py 合成: 消费端只需可见性宏, 全部走默认可见性 */",
        f"#ifndef {guard}",
        f"#define {guard}",
        f"#define {macro}",
        f"#define {no_export}",
        f"#define {prefix}_DEPRECATED __attribute__((__deprecated__))",
        f"#define {prefix}_DEPRECATED_EXPORT {macro}",
        f"#define {prefix}_DEPRECATED_NO_EXPORT {no_export}",
        f"#endif /* {guard} */",
        "",
    ]
    return "\n".join(lines)


def gen_export_headers(source_root: Path, out_dir: Path) -> int:
    """扫描源码树里的 generate_export_header 调用并合成头文件。"""
    out_dir.mkdir(parents=True, exist_ok=True)
    count = 0
    seen: dict[str, str] = {}
    for cmake_file in source_root.rglob("CMakeLists.txt"):
        if any(part in {"autotests", "tests", "examples", ".git"} for part in cmake_file.parts):
            continue
        try:
            text = cmake_file.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        for match in EXPORT_HEADER_RE.finditer(text):
            target, raw_args = match.group(1), match.group(2)
            opts = _parse_export_args(raw_args)
            base = opts.get("BASE_NAME", target)
            macro = opts.get("EXPORT_MACRO_NAME", f"{base.upper()}_EXPORT")
            no_export = opts.get("NO_EXPORT_MACRO_NAME", f"{base.upper()}_NO_EXPORT")
            # ECM 会把 BASE_NAME 小写后作为文件名 (KI18n -> ki18n_export.h)
            filename = opts.get("EXPORT_FILE_NAME", f"{base.lower()}_export.h")
            guard = opts.get("INCLUDE_GUARD_NAME", _export_guard(filename))
            if filename in seen:
                continue
            seen[filename] = macro
            (out_dir / filename).write_text(
                _export_header_text(macro, guard, no_export), encoding="utf-8"
            )
            count += 1
    return count


def gen_extra_export_headers(source_root: Path, out_dir: Path, existing: set[str]) -> int:
    """兜底: 源码里被 include 但没找到 generate_export_header 的 ``*_export.h``。"""
    referenced: set[str] = set()
    pattern = re.compile(r'#\s*include\s*[<"]([A-Za-z0-9_]+_export\.h)[>"]')
    for header in source_root.rglob("*.h"):
        if any(part in {"autotests", "tests", ".git"} for part in header.parts):
            continue
        try:
            text = header.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        referenced.update(pattern.findall(text))
    count = 0
    for name in sorted(referenced - existing):
        base = name[: -len("_export.h")]
        (out_dir / name).write_text(
            _export_header_text(f"{base.upper()}_EXPORT", f"{name.upper()}_H", f"{base.upper()}_NO_EXPORT"),
            encoding="utf-8",
        )
        count += 1
    return count


# ---------------------------------------------------------------- 迷你 configure


def mini_configure(template_text: str, values: dict) -> str:
    def sub_var(match: re.Match) -> str:
        name = match.group(1)
        return str(values.get(name, ""))

    def sub_define(match: re.Match) -> str:
        indent, name = match.group(1), match.group(2)
        value = values.get(name)
        if value is None:
            return f"{indent}/* #undef {name} */"
        return f"{indent}#define {name} {value}"

    def sub_define01(match: re.Match) -> str:
        name = match.group(1)
        return f"#define {name} {1 if values.get(name) else 0}"

    text = CMAKE_DEFINE_RE.sub(sub_define01, template_text)
    text = CMAKE_DEFINE_VAL_RE.sub(sub_define, text)
    text = CMAKE_VAR_RE.sub(sub_var, text)
    return text


def gen_config_headers(krita_src: Path, out_dir: Path, values: dict) -> int:
    """处理仓库根与 libs/ 下的 *.h.cmake 模板, 输出到 out_dir。"""
    out_dir.mkdir(parents=True, exist_ok=True)
    count = 0
    for template in krita_src.rglob("*.h.cmake"):
        rel = template.relative_to(krita_src)
        # 仓库根目录的模板 (rel 只有一段, 如 KoConfig.h.cmake) 与 libs/ 下的都要;
        # 插件/平台扩展 (plugins/...) 跳过, 取值与我们的编译无关
        if len(rel.parts) > 1 and rel.parts[0] != "libs":
            continue
        target_name = template.name[: -len(".cmake")]
        (out_dir / target_name).write_text(
            mini_configure(template.read_text(encoding="utf-8", errors="ignore"), values),
            encoding="utf-8",
        )
        count += 1
    return count


# ---------------------------------------------------------------- KF6 前缀


def build_kf6_prefix(kf6_src: Path, deps_include: Path) -> None:
    kf6_root = deps_include / "KF6"
    kf6_root.mkdir(parents=True, exist_ok=True)
    for module, dirs in KF6_MODULE_DIRS.items():
        module_src = kf6_src / f"{module}-6.6.0"
        if not module_src.is_dir():
            log(f"警告: 缺少 KF6 模块源码 {module_src}")
            continue
        for dst_name in dirs:
            dst = kf6_root / dst_name
            dst.mkdir(parents=True, exist_ok=True)
            # 清掉上一轮合成的 export 头: 合成逻辑变更后必须整体刷新, 否则旧内容残留
            for stale in dst.glob("*_export.h"):
                stale.unlink()
            for header in module_src.rglob("*.h"):
                rel_parts = header.relative_to(module_src).parts
                # 只要公开源码头 (src/ 下), 跳过测试与示例
                if rel_parts and rel_parts[0] in {"autotests", "tests", "examples", "po", "doc"}:
                    continue
                shutil.copy2(header, dst / header.name)
            gen_export_headers(module_src, dst)
            gen_extra_export_headers(module_src, dst, {p.name for p in dst.glob("*_export.h")})


def link_shared_libs(repo: Path, krita_bin_dir: Path, deps_dir: Path) -> int:
    """把仓库内预编译库软链到合成构建目录的 lib/ 下 (供链接器解析)。"""
    sources = [
        repo / "third_party" / "krita-android-libs" / "lib",
        repo / "third_party" / "android-native-libs",
    ]
    count = 0
    for dst_dir in (krita_bin_dir / "lib", deps_dir / "lib"):
        dst_dir.mkdir(parents=True, exist_ok=True)
        for src_dir in sources:
            if not src_dir.is_dir():
                continue
            for lib in sorted(src_dir.glob("*.so")) + sorted(src_dir.glob("*.a")):
                # libreverie_jni.so 由本仓库编译产生, 绝不能链接进去
                if lib.name == "libreverie_jni.so":
                    continue
                link = dst_dir / lib.name
                if link.is_symlink() or link.exists():
                    link.unlink()
                os.symlink(lib.resolve(), link)
                count += 1
    return count


def sync_third_party_headers(headers_src: Path, deps_include: Path) -> None:
    """把 freetype2 / eigen3 / libunibreak 头文件同步进 DEPS_DIR/include。"""
    for name in ("freetype2", "eigen3", "libunibreak"):
        src = headers_src / name
        if not src.is_dir():
            log(f"警告: 缺少第三方头文件 {src} (编译可能因缺失 {name} 而失败)")
            continue
        dst = deps_include / name
        if dst.exists():
            shutil.rmtree(dst)
        shutil.copytree(src, dst, symlinks=True)


# ---------------------------------------------------------------- main


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True, help="ReveriePaint 仓库根")
    parser.add_argument("--krita-src", required=True, help="Krita 源码树")
    parser.add_argument("--kf6-src", required=True, help="KF6 各模块源码的父目录")
    parser.add_argument("--krita-bin-dir", required=True, help="输出: 合成 Krita 构建目录")
    parser.add_argument("--deps-dir", required=True, help="输出: 合成 KF6/第三方 deps 前缀")
    parser.add_argument("--headers-src", default="", help="可选: freetype2/eigen3/libunibreak 头文件来源目录")
    args = parser.parse_args()

    repo = Path(args.repo).resolve()
    krita_src = Path(args.krita_src).resolve()
    kf6_src = Path(args.kf6_src).resolve()
    krita_bin_dir = Path(args.krita_bin_dir).resolve()
    deps_dir = Path(args.deps_dir).resolve()

    if not krita_src.is_dir():
        die(f"Krita 源码目录不存在: {krita_src}")

    # 1. Krita 合成构建目录: config 头 + export 头都放在 libs/global
    #    (CMakeLists 的 include 列表里已有 ${KRITA_BIN_DIR}/libs/global)
    gen_dir = krita_bin_dir / "libs" / "global"
    gen_dir.mkdir(parents=True, exist_ok=True)
    for stale in gen_dir.glob("*_export.h"):
        stale.unlink()
    config_count = gen_config_headers(krita_src, gen_dir, KRITA_CONFIG_VALUES | {
        "KRITA_BUILD_DIR": str(krita_bin_dir),
    })
    export_count = gen_export_headers(krita_src, gen_dir)
    existing = {p.name for p in gen_dir.glob("*_export.h")}
    export_count += gen_extra_export_headers(krita_src, gen_dir, existing)
    log(f"Krita 合成头文件: config {config_count} 个, export {export_count} 个 -> {gen_dir}")

    # 2. KF6 头文件前缀
    build_kf6_prefix(kf6_src, deps_dir / "include")
    log(f"KF6 头文件前缀 -> {deps_dir / 'include' / 'KF6'}")

    # 3. 第三方头文件 (freetype2/eigen3/libunibreak)
    if args.headers_src:
        sync_third_party_headers(Path(args.headers_src).resolve(), deps_dir / "include")

    # 4. 预编译库软链
    linked = link_shared_libs(repo, krita_bin_dir, deps_dir)
    log(f"预编译库软链 {linked} 个 -> {krita_bin_dir / 'lib'}, {deps_dir / 'lib'}")

    log("完成")
    return 0


if __name__ == "__main__":
    sys.exit(main())
