#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 <version-tag> (e.g. v1.11.1 or 1.11.1)" >&2
    exit 1
fi

RAW_TAG="$1"
VERSION="${RAW_TAG#v}"
VERSION="${VERSION#V}"
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

python3 - "$ROOT_DIR/CHANGELOG.md" "$ROOT_DIR/CHANGELOG_ZH.md" "$VERSION" <<'PY'
import re
import sys

en_path, zh_path, version = sys.argv[1], sys.argv[2], sys.argv[3]
heading_re = re.compile(r"^##\s+\[?v?" + re.escape(version) + r"\]?(?:\s*-\s*.*)?$", re.MULTILINE)
next_ver_re = re.compile(r"^##\s+\[?v?\d+\.\d+\.\d+", re.MULTILINE)

def extract_section(path: str) -> str:
    with open(path, "r", encoding="utf-8") as f:
        text = f.read().replace("\r\n", "\n").replace("\r", "\n")
    m = heading_re.search(text)
    if not m:
        raise SystemExit(f"Version v{version} not found in {path}")
    start = text.find("\n", m.end())
    if start == -1:
        raise SystemExit(f"Empty section for v{version} in {path}")
    rest = text[start + 1 :]
    nxt = next_ver_re.search(rest)
    section = rest[: nxt.start()] if nxt else rest
    section = section.strip()
    if not section:
        raise SystemExit(f"Empty section body for v{version} in {path}")
    return section

en_body = extract_section(en_path)
zh_body = extract_section(zh_path)

print("## English\n")
print(en_body)
print("\n---\n")
print("## 简体中文\n")
print(zh_body)
PY
