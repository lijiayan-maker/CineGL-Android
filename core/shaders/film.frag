#version 300 es
// ---- 第 2、3 站「胶片曝光 + 冲洗」：光晕/柔光叠加 → 3D LUT 调色 → 胶片颗粒 ----
precision highp float;
precision highp sampler3D;
#include "common.glsl"

in highp vec2 vUv;
uniform highp sampler2D uInput;        // 工作信号
uniform highp sampler2D uHalation;     // 光晕模糊结果（线性）
uniform highp sampler2D uMist;         // 柔光模糊结果（线性）
uniform highp sampler3D uNeutralLut;   // 中性渲染 LUT
uniform highp sampler3D uLookLut;      // 影调 LUT
uniform float uNeutralLutSize;
uniform float uLookLutSize;
uniform float uLookStrength;           // 影调强度 0..1
uniform int uWorkingEncoding;
uniform float uHalation;               // 0..1
uniform float uMist;                   // 0..1
uniform float uGrain;                  // 0..1
uniform float uGrainSize;              // 颗粒尺寸（像素）
uniform vec2 uFrameSize;               // 画面尺寸（像素）
uniform float uSeed;                   // 每帧变化的随机种子，让颗粒「活」起来
out vec4 fragColor;

// 3D LUT 采样：把 [0,1] 映射到「第一个格点中心 .. 最后一个格点中心」，
// 否则硬件插值会在边缘多出半个格子的偏差（经典坑）
vec3 sampleLut(highp sampler3D lut, float size, vec3 s) {
    vec3 uvw = clamp(s, 0.0, 1.0) * ((size - 1.0) / size) + 0.5 / size;
    return texture(lut, uvw).rgb;
}

// PCG 风格的整数哈希：比 fract(sin(x) * 43758.5) 稳定，不同 GPU 上结果一致
uint hash3(uvec3 v) {
    v = v * 1664525u + 1013904223u;
    v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
    v ^= v >> 16u;
    v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
    return v.x ^ v.y ^ v.z;
}

float random(ivec2 cell, uint seed) {
    return float(hash3(uvec3(uvec2(cell + 65536), seed)) & 0xFFFFFFu) / 16777215.0;
}

// 值噪声：格点上放随机数，格子内平滑插值。比逐像素白噪声更像一团一团的银盐颗粒
float valueNoise(vec2 p, uint seed) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    ivec2 c = ivec2(i);
    float a = random(c, seed);
    float b = random(c + ivec2(1, 0), seed);
    float d = random(c + ivec2(0, 1), seed);
    float e = random(c + ivec2(1, 1), seed);
    return mix(mix(a, b, u.x), mix(d, e, u.x), u.y);
}

void main() {
    vec3 sig = texture(uInput, vUv).rgb;

    if (uHalation > 0.0 || uMist > 0.0) {
        // 光是「加」出来的，所以先解码到线性空间再叠加
        vec3 lin = decodeSignal(sig, uWorkingEncoding);
        if (uMist > 0.0) {
            // 柔光镜：亮部的光被散射到四周 → 高光外扩、暗部被轻微灌亮、反差下降
            vec3 m = texture(uMist, vUv).rgb;
            lin = lin * (1.0 - 0.25 * uMist) + m * (0.9 * uMist);
        }
        if (uHalation > 0.0) {
            // 光晕：强光穿透乳剂、被片基反射回来，再次曝光最底下的红色感光层 → 高光边缘一圈红橙
            vec3 h = texture(uHalation, vUv).rgb;
            float energy = max(h.r, max(h.g, h.b));
            lin += vec3(1.0, 0.28, 0.08) * energy * (2.2 * uHalation);
        }
        sig = encodeSignal(lin, uWorkingEncoding);
    }

    vec3 color = sampleLut(uNeutralLut, uNeutralLutSize, sig);
    if (uLookStrength > 0.0) {
        color = mix(color, sampleLut(uLookLut, uLookLutSize, sig), uLookStrength);
    }

    if (uGrain > 0.0) {
        vec2 gp = vUv * uFrameSize / uGrainSize;
        uint seed = uint(uSeed);
        float n = valueNoise(gp, seed) * 0.65 + valueNoise(gp * 2.17 + 13.7, seed + 101u) * 0.35 - 0.5;
        float chroma = valueNoise(gp * 1.31 + 7.1, seed + 211u) - 0.5;
        // 颗粒在中间调最明显，纯黑纯白处几乎看不到（那里的银盐要么全没、要么全满）
        float l = luma709(color);
        float w = 0.25 + 3.0 * l * (1.0 - l);
        color += (vec3(n) + vec3(chroma, -chroma, chroma) * 0.15) * (uGrain * 0.22 * w);
    }

    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
