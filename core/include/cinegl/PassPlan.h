// CineGL core — 渲染 pass 的「规划」：每个 pass 画多大、参数怎么算。
//
// 这里只有纯数学，不碰任何 GPU API：Engine.cpp 只负责「绑定 + 绘制」，
// 「画多大、参数多少」都在这里算，好处是能在电脑上直接单测。
#pragma once

#include <array>
#include <vector>

namespace cinegl::plan {

struct Size {
    int w = 0;
    int h = 0;
};

enum class GlowKind { Halation, Mist };

/** 光晕 / 柔光的模糊金字塔。level 0 短边固定约 540 像素，所以模糊半径和画面分辨率无关。 */
struct GlowPlan {
    std::vector<Size> levels;
    float threshold = 1.f;   // 线性亮度阈值
    float knee = 0.2f;
    float tapOffsetU = 0.f;  // 预滤波 4 个采样点的偏移（源纹理 uv）
    float tapOffsetV = 0.f;
};

GlowPlan planGlow(int frameW, int frameH, GlowKind kind);

/** 颗粒尺寸：UI 上 0..1 → 像素，并按画面短边相对 1080 缩放（预览和 4000 万像素照片里颗粒看起来一样大）。 */
float grainSizePixels(float grainSize01, int frameW, int frameH);

/** 波形图：先把画面缩到这个尺寸再散点（点数 = w * h）。 */
Size waveformSourceSize(int frameW, int frameH);
constexpr Size kWaveformTarget{256, 128};

/** 波形图在预览视口里的位置（uv，左下原点）：x0, y0, x1, y1，放在右上角。 */
std::array<float, 4> waveformRect(float viewW, float viewH);

/** 缩略图图集：columns 列，行数向上取整。 */
Size thumbnailAtlasSize(int count, int thumbW, int thumbH, int columns);

}  // namespace cinegl::plan
