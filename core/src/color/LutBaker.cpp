#include "cinegl/LutBaker.h"

namespace cinegl::lut {

namespace {

constexpr float kLogShoulderStart = 0.9f;

/** 信号 → 显示线性（[0,1]）。Log 信号可能超过 1，用肩部压回来；709 信号本来就 ≤ 1。 */
Vec3 signalToDisplayLinear(Encoding signal, const Vec3& s) {
    Vec3 lin = color::decode(s, signal);
    if (signal == Encoding::CineLog) {
        lin = {color::highlightRolloff(lin.r, kLogShoulderStart),
               color::highlightRolloff(lin.g, kLogShoulderStart),
               color::highlightRolloff(lin.b, kLogShoulderStart)};
    }
    return clamp01(lin);
}

float angularDistance(float a, float b) {
    float d = std::fabs(std::fmod(a - b, 360.f));
    return d > 180.f ? 360.f - d : d;
}

float overlay(float base, float blend) {
    return base < 0.5f ? 2.f * base * blend : 1.f - 2.f * (1.f - base) * (1.f - blend);
}

Vec3 applyRecipe(const LookRecipe& r, Vec3 lin) {
    // —— 1. 线性空间：曝光、白平衡、串扰、饱和度 ——
    lin *= std::exp2(r.exposure);
    if (r.temperature != 0.f || r.tint != 0.f) {
        lin *= Vec3(std::exp2(0.3f * r.temperature), std::exp2(-0.2f * r.tint), std::exp2(-0.3f * r.temperature));
    }
    if (r.crossTalk > 0.f) {
        const Vec3 mixed{0.80f * lin.r + 0.15f * lin.g + 0.05f * lin.b,
                         0.10f * lin.r + 0.80f * lin.g + 0.10f * lin.b,
                         0.05f * lin.r + 0.15f * lin.g + 0.80f * lin.b};
        lin = mix(lin, mixed, r.crossTalk);
    }
    if (r.mono) {
        const float m = r.monoWeights.r * lin.r + r.monoWeights.g * lin.g + r.monoWeights.b * lin.b;
        lin = Vec3(m);
    } else if (r.saturation != 1.f) {
        const float l = color::luminance(lin);
        lin = maxZero(Vec3(l) + (lin - Vec3(l)) * r.saturation);
    }

    // —— 2. 转到显示编码空间 ——
    Vec3 e{color::linearToSrgb(std::min(lin.r, 1.f)), color::linearToSrgb(std::min(lin.g, 1.f)),
           color::linearToSrgb(std::min(lin.b, 1.f))};

    // —— 3. 色相微调 ——
    if (!r.hues.empty()) {
        Vec3 hsv = color::rgbToHsv(e);
        float hueShift = 0.f, satMul = 1.f;
        for (const HueAdjust& h : r.hues) {
            const float d = angularDistance(hsv.r, h.centerDeg);
            if (d >= h.widthDeg) continue;
            float w = 1.f - d / h.widthDeg;
            w = w * w * (3.f - 2.f * w);
            hueShift += h.shiftDeg * w;
            satMul *= mix(1.f, h.saturation, w);
        }
        hsv.r += hueShift;
        hsv.g = clamp01(hsv.g * satMul);
        e = color::hsvToRgb(hsv);
    }

    // —— 4. S 曲线（逐通道，顺带带来胶片式的饱和度变化）——
    if (r.contrast != 1.f) {
        e = {sCurve(e.r, r.contrast, r.pivot), sCurve(e.g, r.contrast, r.pivot), sCurve(e.b, r.contrast, r.pivot)};
    }

    // —— 5. 分离色调：暗部、亮部各加一点颜色（只改色度，不改亮度）——
    if (r.shadowTintAmount > 0.f || r.highlightTintAmount > 0.f) {
        const float l = color::luminance(e);
        const float ws = 1.f - smoothstep(0.f, 0.55f, l);
        const float wh = smoothstep(0.45f, 1.f, l);
        const Vec3 st = r.shadowTint - Vec3(color::luminance(r.shadowTint));
        const Vec3 ht = r.highlightTint - Vec3(color::luminance(r.highlightTint));
        e += st * (r.shadowTintAmount * ws) + ht * (r.highlightTintAmount * wh);
    }

    // —— 6. 跳漂：和自身亮度做叠加混合，再去掉一部分饱和 ——
    if (r.bleach > 0.f) {
        const float l = color::luminance(e);
        const Vec3 desat = mix(e, Vec3(l), 0.6f);
        const Vec3 o{overlay(desat.r, l), overlay(desat.g, l), overlay(desat.b, l)};
        e = mix(e, o, r.bleach);
    }

    // —— 7. 黑位 / 白位 ——
    if (r.fade != 0.f || r.whitePoint != 1.f) {
        e = Vec3(r.fade) + clamp01(e) * (r.whitePoint - r.fade);
    }
    return clamp01(e);
}

}  // namespace

float sCurve(float x, float contrast, float pivot) {
    x = clamp01(x);
    if (contrast == 1.f || x <= 0.f || x >= 1.f) return x;
    // 先用幂函数把支点挪到 0.5，在 0.5 处套对称的 S 曲线 x^c / (x^c + (1-x)^c)，再挪回去
    const float g = std::log(0.5f) / std::log(pivot);
    const float xp = std::pow(x, g);
    const float a = std::pow(xp, contrast);
    const float b = std::pow(1.f - xp, contrast);
    return std::pow(a / (a + b), 1.f / g);
}

Vec3 evaluateNeutral(Encoding signal, const Vec3& s) {
    const Vec3 lin = signalToDisplayLinear(signal, s);
    return clamp01(color::encode(lin, Encoding::Rec709));
}

Vec3 evaluate(const Look& look, Encoding signal, const Vec3& s) {
    const Vec3 lin = signalToDisplayLinear(signal, s);
    if (look.isCube) {
        if (!look.cube) return clamp01(color::encode(lin, Encoding::Rec709));
        // 约定：导入的 .cube 吃 Rec.709 显示编码（网上绝大多数创意 LUT 都是 709 → 709）
        return clamp01(look.cube->sample(color::encode(lin, Encoding::Rec709)));
    }
    return applyRecipe(look.recipe, lin);
}

namespace {
template <typename F>
std::vector<float> bakeWith(int size, F&& f) {
    std::vector<float> out(static_cast<size_t>(size) * size * size * 4);
    const float inv = 1.f / static_cast<float>(size - 1);
    size_t i = 0;
    for (int b = 0; b < size; ++b) {
        for (int g = 0; g < size; ++g) {
            for (int r = 0; r < size; ++r) {
                const Vec3 c = f(Vec3(r * inv, g * inv, b * inv));
                out[i++] = c.r;
                out[i++] = c.g;
                out[i++] = c.b;
                out[i++] = 1.f;
            }
        }
    }
    return out;
}
}  // namespace

std::vector<float> bake(const Look& look, Encoding signal, int size) {
    return bakeWith(size, [&](const Vec3& s) { return evaluate(look, signal, s); });
}

std::vector<float> bakeNeutral(Encoding signal, int size) {
    return bakeWith(size, [&](const Vec3& s) { return evaluateNeutral(signal, s); });
}

}  // namespace cinegl::lut
