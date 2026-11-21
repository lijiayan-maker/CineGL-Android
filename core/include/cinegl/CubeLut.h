// CineGL core — .cube 3D LUT 解析（Adobe / Resolve 通用格式）
#pragma once

#include <memory>
#include <string>
#include <vector>

#include "cinegl/Color.h"

namespace cinegl {

struct CubeLut {
    std::string title;
    int size = 0;                 // 每个维度的格点数
    Vec3 domainMin{0.f, 0.f, 0.f};
    Vec3 domainMax{1.f, 1.f, 1.f};
    std::vector<Vec3> data;       // size³ 个，R 变化最快，其次 G，最后 B

    const Vec3& at(int r, int g, int b) const { return data[(b * size + g) * size + r]; }

    /** 三线性插值采样（输入按 DOMAIN 归一化）。 */
    Vec3 sample(const Vec3& in) const;
};

/**
 * 解析 .cube 文本。只支持 3D LUT（LUT_3D_SIZE），1D LUT 会报错。
 * 失败返回 nullptr，并把原因写进 error（可为空）。
 */
std::shared_ptr<CubeLut> parseCube(const std::string& text, std::string* error);

}  // namespace cinegl
