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

/** .cube 解析失败的原因。数值与 App 侧的错误文案一一对应，只能追加、不要改已有的值。 */
enum class CubeError {
    None = 0,
    SizeOutOfRange = 1,   // LUT_3D_SIZE 不在 2..256
    Unsupported1D = 2,    // 1D LUT（LUT_1D_SIZE）
    BadDomain = 3,        // DOMAIN_MIN / DOMAIN_MAX 不是 3 个数（line 有效）
    BadData = 4,          // 数据行不是 3 个数（line 有效）
    DataBeforeSize = 5,   // 数据出现在 LUT_3D_SIZE 之前（line 有效）
    CountMismatch = 6,    // 数据行数与 LUT_3D_SIZE³ 不符（count / expected 有效）
    DomainOrder = 7,      // DOMAIN_MAX 没有大于 DOMAIN_MIN
    MissingSize = 8,      // 整个文件都没有 LUT_3D_SIZE
};

/**
 * 失败原因 + 定位信息。核心只给错误码和数字，界面文案由调用方按系统语言生成，
 * 这样核心不用关心界面是中文还是英文。
 */
struct CubeParseError {
    CubeError code = CubeError::None;
    int line = 0;       // 出错的行号（从 1 开始）
    int count = 0;      // CountMismatch：实际的数据行数
    int expected = 0;   // CountMismatch：应有的行数（LUT_3D_SIZE³）

    /** 英文描述，只给日志和单测用。 */
    std::string describe() const;
};

/**
 * 解析 .cube 文本。只支持 3D LUT（LUT_3D_SIZE），1D LUT 会报错。
 * 失败返回 nullptr，并把原因写进 error（可为空）。
 */
std::shared_ptr<CubeLut> parseCube(const std::string& text, CubeParseError* error);

}  // namespace cinegl
