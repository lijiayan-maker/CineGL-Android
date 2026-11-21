#version 300 es
// ---- 上屏：监看辅助（峰值对焦、斑马纹、波形图）+ 圆角 ----
// 这些只画在屏幕上，不会进入录像和照片。
precision highp float;

in highp vec2 vUv;
uniform highp sampler2D uInput;
uniform highp sampler2D uWaveform;
uniform vec2 uTexel;            // 1 / 输入纹理尺寸
uniform vec2 uViewport;         // 视口像素尺寸
uniform float uCornerRadius;    // 圆角半径（像素）
uniform int uZebra;
uniform float uZebraThreshold;
uniform int uPeaking;
uniform float uTime;
uniform vec4 uWaveformRect;     // 波形图在视口里的位置（uv，左下原点）：x0, y0, x1, y1；x1 <= x0 表示关闭
out vec4 fragColor;

float lumaAt(vec2 uv) {
    return dot(texture(uInput, uv).rgb, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
    vec3 c = texture(uInput, vUv).rgb;
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));

    if (uPeaking == 1) {
        // 峰值对焦：亮度梯度大 = 边缘锐利 = 合焦，叠一层红色
        float gx = lumaAt(vUv + vec2(uTexel.x, 0.0)) - lumaAt(vUv - vec2(uTexel.x, 0.0));
        float gy = lumaAt(vUv + vec2(0.0, uTexel.y)) - lumaAt(vUv - vec2(0.0, uTexel.y));
        float edge = length(vec2(gx, gy));
        c = mix(c, vec3(1.0, 0.18, 0.12), smoothstep(0.16, 0.32, edge));
    }

    if (uZebra == 1 && l >= uZebraThreshold) {
        // 斑马纹：过曝区域画会移动的斜条纹
        float stripe = step(0.5, fract((gl_FragCoord.x - gl_FragCoord.y + uTime * 40.0) / 14.0));
        c = mix(c, vec3(stripe), 0.65);
    }

    if (uWaveformRect.z > uWaveformRect.x) {
        vec2 w = (vUv - uWaveformRect.xy) / (uWaveformRect.zw - uWaveformRect.xy);
        if (w.x >= 0.0 && w.x <= 1.0 && w.y >= 0.0 && w.y <= 1.0) {
            vec3 trace = texture(uWaveform, w).rgb;
            // 10% / 50% / 90% 三条参考线
            float px = 1.0 / (uWaveformRect.w - uWaveformRect.y) / uViewport.y;
            float grid = 0.0;
            grid += 1.0 - smoothstep(0.0, px * 1.5, abs(w.y - 0.1));
            grid += 1.0 - smoothstep(0.0, px * 1.5, abs(w.y - 0.5));
            grid += 1.0 - smoothstep(0.0, px * 1.5, abs(w.y - 0.9));
            c = c * 0.3 + trace + vec3(grid * 0.18);
        }
    }

    // 圆角：用圆角矩形的有向距离场（SDF），边缘 1 像素做抗锯齿
    vec2 p = vUv * uViewport;
    vec2 hs = 0.5 * uViewport;
    vec2 q = abs(p - hs) - (hs - vec2(uCornerRadius));
    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - uCornerRadius;
    float alpha = clamp(0.5 - d, 0.0, 1.0);
    fragColor = vec4(c * alpha, 1.0);
}
