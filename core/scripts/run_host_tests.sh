#!/usr/bin/env bash
# 在电脑上编译并运行 core 的单测（只编纯 CPU 部分，不需要手机和 GPU）
set -euo pipefail
cd "$(dirname "$0")/.."

CMAKE="$(command -v cmake || true)"
if [[ -z "$CMAKE" ]]; then
    # 没装 cmake 的话借用 Android SDK 自带的
    CMAKE="$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/cmake/*/bin/cmake 2>/dev/null | tail -1)"
fi
[[ -x "$CMAKE" ]] || { echo "找不到 cmake"; exit 1; }

BUILD=build/host
"$CMAKE" -S . -B "$BUILD" -DCINEGL_BUILD_GLES=OFF -DCINEGL_BUILD_TESTS=ON -DCMAKE_BUILD_TYPE=Debug >/dev/null
"$CMAKE" --build "$BUILD" --parallel
"$BUILD/cinegl_tests"
