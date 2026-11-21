#include "cinegl/Color.h"

namespace cinegl::color {

namespace {
// log2(1 + 63 * 4) = log2(253)
const float kCineLogRange = std::log2(1.f + kCineLogK * kCineLogMaxLinear);
}  // namespace

float srgbToLinear(float c) {
    c = clamp01(c);
    return c <= 0.04045f ? c / 12.92f : std::pow((c + 0.055f) / 1.055f, 2.4f);
}

float linearToSrgb(float x) {
    x = std::max(0.f, x);
    return x <= 0.0031308f ? x * 12.92f : 1.055f * std::pow(x, 1.f / 2.4f) - 0.055f;
}

float cineLogEncode(float x) {
    return std::log2(1.f + kCineLogK * std::max(0.f, x)) / kCineLogRange;
}

float cineLogDecode(float y) {
    return (std::exp2(y * kCineLogRange) - 1.f) / kCineLogK;
}

float decode(float v, Encoding e) { return e == Encoding::CineLog ? cineLogDecode(v) : srgbToLinear(v); }
float encode(float x, Encoding e) { return e == Encoding::CineLog ? cineLogEncode(x) : linearToSrgb(x); }

Vec3 decode(const Vec3& v, Encoding e) { return {decode(v.r, e), decode(v.g, e), decode(v.b, e)}; }
Vec3 encode(const Vec3& x, Encoding e) { return {encode(x.r, e), encode(x.g, e), encode(x.b, e)}; }

float highlightRolloff(float x, float start) {
    if (x <= start) return x;
    const float range = 1.f - start;
    // 在 start 处斜率为 1（和直线段平滑衔接），x → ∞ 时趋于 1
    return start + range * (1.f - std::exp(-(x - start) / range));
}

Vec3 rgbToHsv(const Vec3& c) {
    const float mx = std::max(c.r, std::max(c.g, c.b));
    const float mn = std::min(c.r, std::min(c.g, c.b));
    const float d = mx - mn;
    float h = 0.f;
    if (d > 1e-6f) {
        if (mx == c.r) {
            h = 60.f * std::fmod((c.g - c.b) / d, 6.f);
        } else if (mx == c.g) {
            h = 60.f * ((c.b - c.r) / d + 2.f);
        } else {
            h = 60.f * ((c.r - c.g) / d + 4.f);
        }
    }
    if (h < 0.f) h += 360.f;
    const float s = mx > 1e-6f ? d / mx : 0.f;
    return {h, s, mx};
}

Vec3 hsvToRgb(const Vec3& hsv) {
    float h = std::fmod(hsv.r, 360.f);
    if (h < 0.f) h += 360.f;
    const float s = clamp01(hsv.g);
    const float v = hsv.b;
    const float c = v * s;
    const float x = c * (1.f - std::fabs(std::fmod(h / 60.f, 2.f) - 1.f));
    const float m = v - c;
    Vec3 rgb;
    if (h < 60.f) rgb = {c, x, 0.f};
    else if (h < 120.f) rgb = {x, c, 0.f};
    else if (h < 180.f) rgb = {0.f, c, x};
    else if (h < 240.f) rgb = {0.f, x, c};
    else if (h < 300.f) rgb = {x, 0.f, c};
    else rgb = {c, 0.f, x};
    return rgb + Vec3(m);
}

Vec3 whiteBalanceGains(float kelvin, float tint) {
    kelvin = std::min(15000.f, std::max(1500.f, kelvin));
    // 从 3200K 换到 5500K 大约是 +131 mired，相机里红增益约 ×0.65、蓝增益约 ×1.67，
    // 由此得到每 mired 的「档数」：蓝 0.0055，红 -0.0047。
    const float deltaMired = 1e6f / kelvin - 1e6f / 5500.f;
    Vec3 g{std::exp2(-0.0047f * deltaMired), std::exp2(-0.25f * tint), std::exp2(0.0055f * deltaMired)};
    const float l = luminance(g);
    return g * (1.f / l);
}

}  // namespace cinegl::color
