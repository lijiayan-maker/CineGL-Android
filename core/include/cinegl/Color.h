// CineGL core — 色彩数学（纯 CPU，不依赖 Android，电脑上可单测）
//
// 整个项目里「信号」有两种编码：
//   Rec709  —— 相机平时直出的显示编码（近似 sRGB 曲线），中灰 18% ≈ 0.46
//   CineLog —— 本项目自定义的 Log 曲线：0 → 0，18% 中灰 → 0.454，100% → 0.752，400% → 1.0
// GLSL 里有一份完全一致的实现（shaders/common.glsl），两边必须同步修改。
#pragma once

#include <algorithm>
#include <cmath>

namespace cinegl {

struct Vec3 {
    float r = 0.f, g = 0.f, b = 0.f;

    constexpr Vec3() = default;
    constexpr Vec3(float r_, float g_, float b_) : r(r_), g(g_), b(b_) {}
    constexpr explicit Vec3(float v) : r(v), g(v), b(v) {}

    Vec3 operator+(const Vec3& o) const { return {r + o.r, g + o.g, b + o.b}; }
    Vec3 operator-(const Vec3& o) const { return {r - o.r, g - o.g, b - o.b}; }
    Vec3 operator*(const Vec3& o) const { return {r * o.r, g * o.g, b * o.b}; }
    Vec3 operator/(const Vec3& o) const { return {r / o.r, g / o.g, b / o.b}; }
    Vec3 operator*(float s) const { return {r * s, g * s, b * s}; }
    Vec3& operator+=(const Vec3& o) { r += o.r; g += o.g; b += o.b; return *this; }
    Vec3& operator*=(const Vec3& o) { r *= o.r; g *= o.g; b *= o.b; return *this; }
    Vec3& operator*=(float s) { r *= s; g *= s; b *= s; return *this; }
    float maxComponent() const { return std::max(r, std::max(g, b)); }
};

inline float clamp01(float v) { return std::min(1.f, std::max(0.f, v)); }
inline Vec3 clamp01(const Vec3& v) { return {clamp01(v.r), clamp01(v.g), clamp01(v.b)}; }
inline Vec3 maxZero(const Vec3& v) { return {std::max(0.f, v.r), std::max(0.f, v.g), std::max(0.f, v.b)}; }
inline float mix(float a, float b, float t) { return a + (b - a) * t; }
inline Vec3 mix(const Vec3& a, const Vec3& b, float t) { return a + (b - a) * t; }
inline float smoothstep(float e0, float e1, float x) {
    float t = clamp01((x - e0) / (e1 - e0));
    return t * t * (3.f - 2.f * t);
}

/** 信号编码。数值和 GLSL 里的 int 一一对应，不要改。 */
enum class Encoding : int { Rec709 = 0, CineLog = 1 };

namespace color {

constexpr float kCineLogK = 63.f;
constexpr float kCineLogMaxLinear = 4.f;   // CineLog 1.0 对应的线性值（比白高 2 档）

float srgbToLinear(float c);
float linearToSrgb(float x);
float cineLogEncode(float x);
float cineLogDecode(float y);

float decode(float v, Encoding e);
float encode(float x, Encoding e);
Vec3 decode(const Vec3& v, Encoding e);
Vec3 encode(const Vec3& x, Encoding e);

/** Rec.709 亮度权重（作用在线性值上才是「真亮度」，作用在编码值上是近似的 luma）。 */
inline float luminance(const Vec3& c) { return 0.2126f * c.r + 0.7152f * c.g + 0.0722f * c.b; }

/**
 * 高光肩部：x <= start 时原样输出，之后平滑地逼近 1。
 * Log 信号能表示 1.0 以上的线性值（光晕会把高光推上去），显示前用它把超出的部分「压」回来，
 * 而不是一刀切掉——胶片的高光就是这样慢慢饱和的。
 */
float highlightRolloff(float x, float start);

/** HSV（h 单位：度，0..360；s、v 0..1）。 */
Vec3 rgbToHsv(const Vec3& rgb);
Vec3 hsvToRgb(const Vec3& hsv);

/**
 * 白平衡相对增益（线性 RGB，作用在「已按 5500K 平衡过」的画面上）。
 * 用 mired（1e6 / K）差值建模：设成更低的色温 → 补蓝减红，画面变冷，和相机行为一致。
 * tint：-1 偏绿 .. +1 偏品。返回值按亮度归一，调色温不改变整体亮度。
 */
Vec3 whiteBalanceGains(float kelvin, float tint);

}  // namespace color
}  // namespace cinegl
