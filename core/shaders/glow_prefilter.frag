#version 300 es
// ---- 光晕/柔光第 1 步：提取高光 + 降采样 ----
// 从全分辨率工作信号缩小到「短边约 540 像素」的 level 0，同时只保留比阈值亮的部分。
precision highp float;
#include "common.glsl"

in highp vec2 vUv;
uniform highp sampler2D uInput;
uniform int uWorkingEncoding;
uniform vec2 uTapOffset;    // 4 个采样点相对中心的偏移（输入纹理 uv 单位）
uniform float uThreshold;   // 线性亮度阈值
uniform float uKnee;        // 软膝宽度：阈值附近平滑过渡，避免高光边缘「一刀切」
out vec4 fragColor;

void main() {
    // 4 个双线性采样 ≈ 一个 4x4 的盒式滤波，缩小时不容易闪烁
    vec3 c = decodeSignal(texture(uInput, vUv + vec2(-uTapOffset.x, -uTapOffset.y)).rgb, uWorkingEncoding)
           + decodeSignal(texture(uInput, vUv + vec2( uTapOffset.x, -uTapOffset.y)).rgb, uWorkingEncoding)
           + decodeSignal(texture(uInput, vUv + vec2(-uTapOffset.x,  uTapOffset.y)).rgb, uWorkingEncoding)
           + decodeSignal(texture(uInput, vUv + vec2( uTapOffset.x,  uTapOffset.y)).rgb, uWorkingEncoding);
    c *= 0.25;

    float brightness = max(c.r, max(c.g, c.b));
    float soft = clamp(brightness - uThreshold + uKnee, 0.0, 2.0 * uKnee);
    soft = soft * soft / (4.0 * uKnee + 1e-4);
    float contribution = max(soft, brightness - uThreshold) / max(brightness, 1e-4);
    fragColor = vec4(c * contribution, 1.0);
}
