#!/usr/bin/env bash
# 离线检查所有 GLSL：展开 #include 后用 Android NDK 自带的 glslc 编译一遍，不需要手机。
# 能抓住 uniform 重名、类型不匹配、拼错函数名这类「到真机上才 crash」的错误。
set -euo pipefail
cd "$(dirname "$0")/.."

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
GLSLC="$(ls -d "$SDK"/ndk/*/shader-tools/*/glslc 2>/dev/null | tail -1 || true)"
[[ -x "$GLSLC" ]] || { echo "找不到 glslc（在 Android NDK 的 shader-tools 目录里）"; exit 1; }

# 顺便编出 cinegl_tests，用它的 --dump-shaders 展开 #include（和运行时同一套逻辑）
./scripts/run_host_tests.sh >/dev/null
OUT=build/shaders
rm -rf "$OUT" && mkdir -p "$OUT"
build/host/cinegl_tests --dump-shaders "$OUT"

failed=0
for f in "$OUT"/*.vert "$OUT"/*.frag; do
    name="$(basename "$f")"
    stage=frag
    [[ "$name" == *.vert ]] && stage=vert
    # glslc 只会输出 SPIR-V，而 SPIR-V 不接受 ES 3.00，所以借 ES 3.10（3.00 的超集）做语法和类型检查；
    # 外部纹理是 Android 扩展，SPIR-V 不认识，换成普通 2D 纹理检查（函数体和 input_2d.frag 是同一份）
    sed -e 's/#version 300 es/#version 310 es/' \
        -e '/GL_OES_EGL_image_external_essl3/d' \
        -e 's/samplerExternalOES/sampler2D/' "$f" > "$f.check"
    if "$GLSLC" --target-env=opengl -fauto-map-locations -fauto-bind-uniforms -fshader-stage="$stage" \
        "$f.check" -o /dev/null; then
        echo "✓ $name"
    else
        echo "✗ $name"
        failed=1
    fi
done
exit $failed
