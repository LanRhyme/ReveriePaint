#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/test_liquify_kernel.py
g++ -std=c++17 -O2 -Wall -Wextra -Ibuild tests/native/liquify_kernel_shortcut_test.cpp -o build/liquify-kernel-shortcut
./build/liquify-kernel-shortcut
g++ -std=c++17 -O2 -Wall -Wextra -Ibuild tests/native/liquify_dab_shortcut_test.cpp -lEGL -lGLESv2 -o build/liquify-dab-shortcut
EGL_PLATFORM=surfaceless LIBGL_ALWAYS_SOFTWARE=1 ./build/liquify-dab-shortcut
