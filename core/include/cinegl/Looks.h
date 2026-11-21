// CineGL core — 影调（Look）定义与注册表
//
// 一个影调 = 一组「配方」参数（曝光、白平衡、饱和、S 曲线、分离色调、色相微调……），
// 或者一张用户导入的 .cube。配方在 CPU 上被「烘焙」成 3D LUT（见 LutBaker.h），
// GPU 每个像素只需要查一次表——这就是实时调色的基本套路。
#pragma once

#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "cinegl/Color.h"
#include "cinegl/CubeLut.h"

namespace cinegl {

/** 针对某个色相区间的微调（例如把皮肤推向橙色、把绿色推向橄榄色）。 */
struct HueAdjust {
    float centerDeg = 0.f;   // 区间中心色相
    float widthDeg = 30.f;   // 半宽：距离中心超过它就不受影响
    float shiftDeg = 0.f;    // 色相偏移
    float saturation = 1.f;  // 饱和度倍数
};

struct LookRecipe {
    // —— 线性空间 ——
    float exposure = 0.f;       // 档
    float temperature = 0.f;    // -1 冷 .. +1 暖
    float tint = 0.f;           // -1 绿 .. +1 品
    float crossTalk = 0.f;      // 通道串扰 0..1：胶片三层乳剂互相「串色」，让颜色更柔和
    float saturation = 1.f;
    bool mono = false;          // 黑白
    Vec3 monoWeights{0.30f, 0.59f, 0.11f};

    // —— 显示编码空间（更接近人眼感受）——
    std::vector<HueAdjust> hues;
    float contrast = 1.f;       // S 曲线斜率（1 = 不变）
    float pivot = 0.43f;        // S 曲线支点
    Vec3 shadowTint{0.f, 0.f, 0.f};
    float shadowTintAmount = 0.f;
    Vec3 highlightTint{0.f, 0.f, 0.f};
    float highlightTintAmount = 0.f;
    float bleach = 0.f;         // 跳漂（留银）0..1
    float fade = 0.f;           // 黑位抬升（褪色感）
    float whitePoint = 1.f;     // 白位
};

struct Look {
    std::string id;
    std::string name;     // 中文名
    std::string nameEn;
    bool isCube = false;
    LookRecipe recipe;
    std::shared_ptr<const CubeLut> cube;   // isCube 时有效；约定输入为 Rec.709 显示编码
};

/** 全局影调表：内置影调 + 运行时导入的 .cube。线程安全。 */
class LookLibrary {
public:
    static LookLibrary& instance();

    int count() const;
    /** 越界返回 false。返回的是拷贝，可以放心在别的线程用。 */
    bool get(int index, Look* out) const;
    int indexOf(const std::string& id) const;
    /** 注册（或替换同 id 的）.cube 影调，返回索引。 */
    int addCube(const std::string& id, const std::string& name, std::shared_ptr<const CubeLut> cube);

private:
    LookLibrary();
    mutable std::mutex mutex_;
    std::vector<Look> looks_;
};

}  // namespace cinegl
