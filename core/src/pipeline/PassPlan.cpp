#include "cinegl/PassPlan.h"

#include <algorithm>
#include <cmath>

namespace cinegl::plan {

GlowPlan planGlow(int frameW, int frameH, GlowKind kind) {
    GlowPlan p;
    const int shortSide = std::max(1, std::min(frameW, frameH));
    const float baseShort = std::min(540.f, shortSide * 0.5f);
    const float scale = baseShort / static_cast<float>(shortSide);

    // 光晕紧贴高光（4 级），柔光要铺得很开（6 级）
    const int levelCount = kind == GlowKind::Halation ? 4 : 6;
    p.threshold = kind == GlowKind::Halation ? 0.8f : 0.4f;
    p.knee = kind == GlowKind::Halation ? 0.2f : 0.3f;

    Size s{std::max(1, static_cast<int>(std::lround(frameW * scale))),
           std::max(1, static_cast<int>(std::lround(frameH * scale)))};
    for (int i = 0; i < levelCount; ++i) {
        p.levels.push_back(s);
        s = {std::max(1, s.w / 2), std::max(1, s.h / 2)};
    }
    // 采样点放在 level 0 一个像素的 ±1/4 处：2 倍缩小时正好覆盖 2x2 个源像素
    p.tapOffsetU = 0.25f / static_cast<float>(p.levels[0].w);
    p.tapOffsetV = 0.25f / static_cast<float>(p.levels[0].h);
    return p;
}

float grainSizePixels(float grainSize01, int frameW, int frameH) {
    const float shortSide = static_cast<float>(std::max(1, std::min(frameW, frameH)));
    return (1.f + 2.5f * std::min(1.f, std::max(0.f, grainSize01))) * shortSide / 1080.f;
}

Size waveformSourceSize(int frameW, int frameH) {
    const int w = std::max(1, std::min(192, frameW / 4));
    const int h = std::max(1, std::min(256, static_cast<int>(std::lround(w * static_cast<float>(frameH) / frameW))));
    return {w, h};
}

std::array<float, 4> waveformRect(float viewW, float viewH) {
    if (viewW <= 0.f || viewH <= 0.f) return {0.f, 0.f, 0.f, 0.f};
    const float margin = 0.03f * viewW;
    const float w = 0.42f * viewW;
    const float h = w * 0.5f;
    const float x1 = 1.f - margin / viewW;
    const float y1 = 1.f - margin / viewH;
    return {x1 - w / viewW, y1 - h / viewH, x1, y1};
}

Size thumbnailAtlasSize(int count, int thumbW, int thumbH, int columns) {
    columns = std::max(1, columns);
    const int rows = std::max(1, (count + columns - 1) / columns);
    return {columns * thumbW, rows * thumbH};
}

}  // namespace cinegl::plan
