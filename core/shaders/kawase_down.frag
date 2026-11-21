#version 300 es
// ---- Dual Kawase 模糊：降采样 ----
// 每一级尺寸减半：中心采样权重 4，四个对角采样各 1。级数越多，模糊半径越大，
// 而每一级的像素数只有上一级的 1/4，总开销收敛——这就是它在手机上比高斯模糊省得多的原因。
precision highp float;

in highp vec2 vUv;
uniform highp sampler2D uInput;
uniform vec2 uHalfTexel;   // 0.5 / 输入尺寸
uniform float uOffset;
out vec4 fragColor;

void main() {
    vec2 o = uHalfTexel * uOffset;
    vec4 sum = texture(uInput, vUv) * 4.0;
    sum += texture(uInput, vUv - o);
    sum += texture(uInput, vUv + o);
    sum += texture(uInput, vUv + vec2(o.x, -o.y));
    sum += texture(uInput, vUv - vec2(o.x, -o.y));
    fragColor = sum / 8.0;
}
