#include "cinegl/Looks.h"

namespace cinegl {

namespace {

Look recipeLook(const char* id, const char* name, const char* nameEn, const LookRecipe& r) {
    Look look;
    look.id = id;
    look.name = name;
    look.nameEn = nameEn;
    look.recipe = r;
    return look;
}

std::vector<Look> builtInLooks() {
    std::vector<Look> looks;

    // 原生：什么都不做，用来单独看「镜头 + 胶片效果」
    looks.push_back(recipeLook("natural", "原生", "Natural", LookRecipe{}));

    {   // 青橙：好莱坞大片的经典配色，肤色推橙、环境推青
        LookRecipe r;
        r.contrast = 1.18f;
        r.saturation = 1.05f;
        r.hues = {
            {30.f, 35.f, 4.f, 1.15f},     // 橙/肤色：略往橙推、加饱和
            {120.f, 50.f, 45.f, 0.75f},   // 绿 → 青
            {200.f, 45.f, -8.f, 1.1f},    // 青蓝：统一到青
        };
        r.shadowTint = {0.0f, 0.45f, 0.55f};
        r.shadowTintAmount = 0.10f;
        r.highlightTint = {1.0f, 0.62f, 0.3f};
        r.highlightTintAmount = 0.06f;
        looks.push_back(recipeLook("teal_orange", "青橙", "Teal & Orange", r));
    }
    {   // 暖阳：低反差、暖白、柔和的人像胶片感
        LookRecipe r;
        r.temperature = 0.35f;
        r.crossTalk = 0.25f;
        r.saturation = 0.92f;
        r.contrast = 0.92f;
        r.hues = {{100.f, 40.f, -10.f, 0.85f}};   // 绿色偏黄一点
        r.highlightTint = {1.0f, 0.85f, 0.65f};
        r.highlightTintAmount = 0.06f;
        r.fade = 0.035f;
        looks.push_back(recipeLook("warm_film", "暖阳", "Warm Film", r));
    }
    {   // 冷冽：北欧风，低饱和、冷影
        LookRecipe r;
        r.temperature = -0.35f;
        r.saturation = 0.72f;
        r.contrast = 1.06f;
        r.shadowTint = {0.2f, 0.4f, 0.75f};
        r.shadowTintAmount = 0.08f;
        r.fade = 0.03f;
        looks.push_back(recipeLook("nordic", "冷冽", "Nordic", r));
    }
    {   // 褪色：低对比、黑位发灰、带一点青绿
        LookRecipe r;
        r.contrast = 0.82f;
        r.saturation = 0.8f;
        r.crossTalk = 0.2f;
        r.shadowTint = {0.2f, 0.55f, 0.5f};
        r.shadowTintAmount = 0.07f;
        r.fade = 0.09f;
        r.whitePoint = 0.96f;
        looks.push_back(recipeLook("matte", "褪色", "Matte", r));
    }
    {   // 银盐：红滤镜味道的高反差黑白
        LookRecipe r;
        r.mono = true;
        r.monoWeights = {0.42f, 0.48f, 0.10f};
        r.contrast = 1.28f;
        r.fade = 0.015f;
        looks.push_back(recipeLook("silver", "银盐", "Silver", r));
    }
    {   // 霓虹：夜景用，红推品、蓝推青，冷暖对撞
        LookRecipe r;
        r.temperature = -0.15f;
        r.saturation = 1.2f;
        r.contrast = 1.1f;
        r.hues = {
            {0.f, 30.f, -14.f, 1.2f},      // 红 → 品
            {220.f, 45.f, -22.f, 1.15f},   // 蓝 → 青
        };
        r.shadowTint = {0.35f, 0.2f, 0.7f};
        r.shadowTintAmount = 0.08f;
        r.highlightTint = {1.0f, 0.4f, 0.75f};
        r.highlightTintAmount = 0.05f;
        looks.push_back(recipeLook("neon", "霓虹", "Neon Night", r));
    }
    {   // 森屿：绿色压成橄榄、整体偏暖、轻微褪色
        LookRecipe r;
        r.temperature = 0.12f;
        r.contrast = 1.04f;
        r.hues = {
            {110.f, 50.f, -22.f, 0.7f},   // 绿 → 橄榄黄绿，降饱和
            {200.f, 40.f, 10.f, 0.8f},
        };
        r.highlightTint = {1.0f, 0.9f, 0.6f};
        r.highlightTintAmount = 0.05f;
        r.fade = 0.03f;
        looks.push_back(recipeLook("forest", "森屿", "Forest", r));
    }
    {   // 黄昏：大面积暖橙，亮部金色
        LookRecipe r;
        r.temperature = 0.6f;
        r.tint = 0.08f;
        r.saturation = 1.08f;
        r.contrast = 1.06f;
        r.highlightTint = {1.0f, 0.68f, 0.25f};
        r.highlightTintAmount = 0.08f;
        r.shadowTint = {0.45f, 0.25f, 0.4f};
        r.shadowTintAmount = 0.05f;
        looks.push_back(recipeLook("golden_hour", "黄昏", "Golden Hour", r));
    }
    {   // 漂白：跳过漂白工艺的银盐残留——低饱和、高反差、金属感
        LookRecipe r;
        r.saturation = 0.85f;
        r.contrast = 1.12f;
        r.bleach = 0.75f;
        looks.push_back(recipeLook("bleach", "漂白", "Bleach Bypass", r));
    }
    {   // 浓彩：反转片式的浓郁红蓝、深阴影
        LookRecipe r;
        r.saturation = 1.18f;
        r.contrast = 1.2f;
        r.pivot = 0.46f;
        r.hues = {
            {0.f, 35.f, 0.f, 1.2f},      // 红更浓
            {225.f, 40.f, 5.f, 1.2f},    // 蓝更深
            {55.f, 25.f, -6.f, 0.9f},    // 黄略压
        };
        looks.push_back(recipeLook("chrome", "浓彩", "Rich Chrome", r));
    }
    return looks;
}

}  // namespace

LookLibrary& LookLibrary::instance() {
    static LookLibrary library;
    return library;
}

LookLibrary::LookLibrary() : looks_(builtInLooks()) {}

int LookLibrary::count() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return static_cast<int>(looks_.size());
}

bool LookLibrary::get(int index, Look* out) const {
    std::lock_guard<std::mutex> lock(mutex_);
    if (index < 0 || index >= static_cast<int>(looks_.size())) return false;
    *out = looks_[index];
    return true;
}

int LookLibrary::indexOf(const std::string& id) const {
    std::lock_guard<std::mutex> lock(mutex_);
    for (size_t i = 0; i < looks_.size(); ++i) {
        if (looks_[i].id == id) return static_cast<int>(i);
    }
    return -1;
}

int LookLibrary::addCube(const std::string& id, const std::string& name, std::shared_ptr<const CubeLut> cube) {
    std::lock_guard<std::mutex> lock(mutex_);
    Look look;
    look.id = id;
    look.name = name;
    look.nameEn = cube && !cube->title.empty() ? cube->title : "Imported LUT";
    look.isCube = true;
    look.cube = std::move(cube);
    for (size_t i = 0; i < looks_.size(); ++i) {
        if (looks_[i].id == id) {
            looks_[i] = look;
            return static_cast<int>(i);
        }
    }
    looks_.push_back(look);
    return static_cast<int>(looks_.size() - 1);
}

}  // namespace cinegl
