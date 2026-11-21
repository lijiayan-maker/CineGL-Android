#version 300 es
// ---- Dual Kawase 模糊：升采样 ----
// 8 个采样排成菱形（4 个轴向权重 1，4 个对角权重 2），一级级放大回去，结果是又宽又平滑的模糊。
precision highp float;

in highp vec2 vUv;
uniform highp sampler2D uInput;
uniform vec2 uHalfTexel;   // 0.5 / 输入尺寸（输入是更小的那一级）
uniform float uOffset;
out vec4 fragColor;

void main() {
    vec2 o = uHalfTexel * uOffset;
    vec4 sum = texture(uInput, vUv + vec2(-o.x * 2.0, 0.0));
    sum += texture(uInput, vUv + vec2(-o.x, o.y)) * 2.0;
    sum += texture(uInput, vUv + vec2(0.0, o.y * 2.0));
    sum += texture(uInput, vUv + vec2(o.x, o.y)) * 2.0;
    sum += texture(uInput, vUv + vec2(o.x * 2.0, 0.0));
    sum += texture(uInput, vUv + vec2(o.x, -o.y)) * 2.0;
    sum += texture(uInput, vUv + vec2(0.0, -o.y * 2.0));
    sum += texture(uInput, vUv + vec2(-o.x, -o.y)) * 2.0;
    fragColor = sum / 12.0;
}
