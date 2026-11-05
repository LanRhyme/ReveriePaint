#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# 用法: CXX=clang++ bash app/src/test/cpp/run_cpp_tests.sh <test_name>...
# 在仓库根目录执行; 每个 test_name 对应 app/src/test/cpp/<test_name>.cpp
set -euo pipefail
CXX="${CXX:-clang++}"
mkdir -p build/cpptest
for t in "$@"; do
  echo "== $t =="
  $CXX -std=c++17 -O1 -Iapp/src/main/cpp -Iapp/src/test/cpp "app/src/test/cpp/$t.cpp" -o "build/cpptest/$t"
  "build/cpptest/$t"
done
