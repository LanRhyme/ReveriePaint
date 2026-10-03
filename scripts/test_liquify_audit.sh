#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/test_liquify_kernel.sh
bash scripts/test_liquify_live_preview.sh
g++ -std=c++17 -O2 -Wall -Wextra tests/native/liquify_professional_test.cpp -o build/liquify-professional
./build/liquify-professional
g++ -std=c++17 -O2 -Wall -Wextra tests/native/liquify_pixel_sampling_test.cpp -o build/liquify-pixel-sampling
./build/liquify-pixel-sampling
g++ -std=c++17 -O2 -Wall -Wextra tests/native/liquify_sampling_cache_test.cpp -o build/liquify-sampling-cache
./build/liquify-sampling-cache
python3 scripts/test_liquify_scene.py
g++ -std=c++17 -O2 -Wall -Wextra -Ibuild tests/native/liquify_scene_test.cpp -lEGL -lGLESv2 -o build/liquify-scene-audit
EGL_PLATFORM=surfaceless LIBGL_ALWAYS_SOFTWARE=1 ./build/liquify-scene-audit
