#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build
g++ -std=c++17 -O2 -Wall -Wextra tests/native/liquify_preview_region_test.cpp -o build/liquify-preview-region
./build/liquify-preview-region
