// core 的主机单测：不依赖任何测试框架，macOS / Linux 上直接编译运行。
//   core/scripts/run_host_tests.sh
#include <cmath>
#include <cstdio>
#include <functional>
#include <string>
#include <vector>

#include "cinegl/Color.h"
#include "cinegl/CubeLut.h"
#include "cinegl/FrameGeometry.h"
#include "cinegl/Looks.h"
#include "cinegl/LutBaker.h"
#include "cinegl/PassPlan.h"
#include "pipeline/ShaderLibrary.h"

using namespace cinegl;

namespace {

int gFailures = 0;
int gChecks = 0;

#define CHECK(cond)                                                              \
    do {                                                                         \
        ++gChecks;                                                               \
        if (!(cond)) {                                                           \
            ++gFailures;                                                         \
            std::printf("  FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond);        \
        }                                                                        \
    } while (0)

#define CHECK_NEAR(a, b, eps)                                                                     \
    do {                                                                                          \
        ++gChecks;                                                                                \
        const double va = (a), vb = (b);                                                          \
        if (!(std::fabs(va - vb) <= (eps))) {                                                     \
            ++gFailures;                                                                          \
            std::printf("  FAIL %s:%d  %s = %.6f, expected %.6f (±%g)\n", __FILE__, __LINE__, #a, \
                        va, vb, static_cast<double>(eps));                                        \
        }                                                                                         \
    } while (0)

struct TestCase {
    const char* name;
    std::function<void()> body;
};

std::vector<TestCase>& registry() {
    static std::vector<TestCase> tests;
    return tests;
}

struct Register {
    Register(const char* name, std::function<void()> body) { registry().push_back({name, std::move(body)}); }
};

#define TEST(name)                                   \
    void name();                                     \
    const Register name##_registered(#name, name);   \
    void name()

std::string identityCube(int n) {
    std::string s = "TITLE \"identity\"\n# comment\nLUT_3D_SIZE " + std::to_string(n) + "\n";
    for (int b = 0; b < n; ++b)
        for (int g = 0; g < n; ++g)
            for (int r = 0; r < n; ++r) {
                char line[96];
                std::snprintf(line, sizeof(line), "%.6f %.6f %.6f\n", r / float(n - 1), g / float(n - 1),
                              b / float(n - 1));
                s += line;
            }
    return s;
}

}  // namespace

// ------------------------------------------------------------------ 色彩

TEST(cineLogKeyPoints) {
    CHECK_NEAR(color::cineLogEncode(0.f), 0.0, 1e-6);
    CHECK_NEAR(color::cineLogEncode(0.18f), 0.4541, 1e-3);   // 中灰
    CHECK_NEAR(color::cineLogEncode(1.f), 0.7516, 1e-3);     // 100% 白
    CHECK_NEAR(color::cineLogEncode(4.f), 1.0, 1e-5);        // 2 档余量的顶
    for (float x : {0.f, 0.001f, 0.05f, 0.18f, 0.9f, 2.5f, 4.f}) {
        CHECK_NEAR(color::cineLogDecode(color::cineLogEncode(x)), x, 1e-4 + x * 1e-5);
    }
}

TEST(srgbRoundTrip) {
    for (float c = 0.f; c <= 1.f; c += 0.05f) {
        CHECK_NEAR(color::linearToSrgb(color::srgbToLinear(c)), c, 1e-5);
    }
    CHECK_NEAR(color::linearToSrgb(0.18f), 0.4614, 1e-3);
}

TEST(hsvRoundTrip) {
    const Vec3 samples[] = {{0.9f, 0.2f, 0.1f}, {0.1f, 0.8f, 0.3f}, {0.2f, 0.3f, 0.9f}, {0.5f, 0.5f, 0.5f}};
    for (const Vec3& c : samples) {
        const Vec3 back = color::hsvToRgb(color::rgbToHsv(c));
        CHECK_NEAR(back.r, c.r, 1e-5);
        CHECK_NEAR(back.g, c.g, 1e-5);
        CHECK_NEAR(back.b, c.b, 1e-5);
    }
}

TEST(whiteBalanceGains) {
    const Vec3 neutral = color::whiteBalanceGains(5500.f, 0.f);
    CHECK_NEAR(neutral.r, 1.0, 1e-5);
    CHECK_NEAR(neutral.g, 1.0, 1e-5);
    CHECK_NEAR(neutral.b, 1.0, 1e-5);
    const Vec3 tungsten = color::whiteBalanceGains(3200.f, 0.f);   // 设成钨丝灯 → 画面变冷
    CHECK(tungsten.b > tungsten.g && tungsten.g > tungsten.r);
    CHECK_NEAR(color::luminance(tungsten), 1.0, 1e-5);
    const Vec3 shade = color::whiteBalanceGains(8000.f, 0.f);       // 设成阴天 → 画面变暖
    CHECK(shade.r > shade.b);
    const Vec3 magenta = color::whiteBalanceGains(5500.f, 1.f);
    CHECK(magenta.g < magenta.r);
}

TEST(highlightRolloff) {
    CHECK_NEAR(color::highlightRolloff(0.5f, 0.9f), 0.5, 1e-6);
    CHECK(color::highlightRolloff(1.f, 0.9f) < 1.f);
    CHECK(color::highlightRolloff(4.f, 0.9f) <= 1.f);
    CHECK(color::highlightRolloff(2.f, 0.9f) > color::highlightRolloff(1.f, 0.9f));
}

// ------------------------------------------------------------------ LUT

TEST(sCurveFixesEndpointsAndPivot) {
    CHECK_NEAR(lut::sCurve(0.f, 1.3f, 0.43f), 0.0, 1e-6);
    CHECK_NEAR(lut::sCurve(1.f, 1.3f, 0.43f), 1.0, 1e-6);
    CHECK_NEAR(lut::sCurve(0.43f, 1.3f, 0.43f), 0.43, 1e-4);
    CHECK(lut::sCurve(0.2f, 1.3f, 0.43f) < 0.2f);   // 暗部更暗
    CHECK(lut::sCurve(0.8f, 1.3f, 0.43f) > 0.8f);   // 亮部更亮
    CHECK_NEAR(lut::sCurve(0.3f, 1.f, 0.43f), 0.3, 1e-6);
}

TEST(neutralRec709IsIdentity) {
    const int n = 9;
    const std::vector<float> data = lut::bakeNeutral(Encoding::Rec709, n);
    CHECK(data.size() == size_t(n * n * n * 4));
    double maxErr = 0.0;
    for (int b = 0; b < n; ++b)
        for (int g = 0; g < n; ++g)
            for (int r = 0; r < n; ++r) {
                const size_t i = size_t(((b * n + g) * n + r) * 4);
                maxErr = std::max(maxErr, double(std::fabs(data[i] - r / float(n - 1))));
                maxErr = std::max(maxErr, double(std::fabs(data[i + 1] - g / float(n - 1))));
                maxErr = std::max(maxErr, double(std::fabs(data[i + 2] - b / float(n - 1))));
            }
    CHECK(maxErr < 1e-4);
}

TEST(neutralLogDecodesToDisplay) {
    // Log 信号里的中灰，经中性 LUT 应该还原成 sRGB 的中灰
    const float grey = color::cineLogEncode(0.18f);
    const Vec3 out = lut::evaluateNeutral(Encoding::CineLog, Vec3(grey));
    CHECK_NEAR(out.r, color::linearToSrgb(0.18f), 1e-3);
    // Log 的 100% 白被肩部压到略低于 1
    const Vec3 white = lut::evaluateNeutral(Encoding::CineLog, Vec3(color::cineLogEncode(1.f)));
    CHECK(white.r > 0.97f && white.r < 1.f);
}

TEST(naturalLookEqualsNeutral) {
    LookLibrary& lib = LookLibrary::instance();
    Look natural;
    CHECK(lib.get(lib.indexOf("natural"), &natural));
    for (float v : {0.f, 0.1f, 0.35f, 0.6f, 0.95f}) {
        const Vec3 s{v, 1.f - v, v * 0.5f};
        for (Encoding e : {Encoding::Rec709, Encoding::CineLog}) {
            const Vec3 a = lut::evaluate(natural, e, s);
            const Vec3 b = lut::evaluateNeutral(e, s);
            CHECK_NEAR(a.r, b.r, 1e-5);
            CHECK_NEAR(a.g, b.g, 1e-5);
            CHECK_NEAR(a.b, b.b, 1e-5);
        }
    }
}

TEST(allBuiltInLooksBakeCleanly) {
    LookLibrary& lib = LookLibrary::instance();
    CHECK(lib.count() >= 10);
    for (int i = 0; i < lib.count(); ++i) {
        Look look;
        CHECK(lib.get(i, &look));
        for (Encoding e : {Encoding::Rec709, Encoding::CineLog}) {
            const std::vector<float> data = lut::bake(look, e, 9);
            bool clean = true;
            for (float v : data) clean = clean && std::isfinite(v) && v >= 0.f && v <= 1.f;
            if (!clean) std::printf("  look %s produced out-of-range values\n", look.id.c_str());
            CHECK(clean);
        }
    }
}

TEST(silverLookIsMonochrome) {
    LookLibrary& lib = LookLibrary::instance();
    Look silver;
    CHECK(lib.get(lib.indexOf("silver"), &silver));
    const Vec3 out = lut::evaluate(silver, Encoding::Rec709, {0.8f, 0.3f, 0.1f});
    CHECK_NEAR(out.r, out.g, 1e-4);
    CHECK_NEAR(out.g, out.b, 1e-4);
}

// ------------------------------------------------------------------ .cube

TEST(cubeParseAndSample) {
    std::string err;
    auto cube = parseCube(identityCube(5), &err);
    CHECK(cube != nullptr);
    if (!cube) return;
    CHECK(cube->size == 5);
    CHECK(cube->title == "identity");
    const Vec3 s = cube->sample({0.3f, 0.62f, 0.91f});
    CHECK_NEAR(s.r, 0.3, 1e-5);
    CHECK_NEAR(s.g, 0.62, 1e-5);
    CHECK_NEAR(s.b, 0.91, 1e-5);
}

TEST(cubeRejectsBadInput) {
    std::string err;
    CHECK(parseCube("LUT_1D_SIZE 16\n", &err) == nullptr);
    CHECK(!err.empty());
    CHECK(parseCube("LUT_3D_SIZE 2\n0 0 0\n1 1 1\n", &err) == nullptr);   // 数量不够
    CHECK(parseCube("0 0 0\n", &err) == nullptr);                          // 没有尺寸
    CHECK(parseCube("LUT_3D_SIZE 2\n0 0 zero\n", &err) == nullptr);
}

TEST(cubeLookBakeOrderAndLogInput) {
    std::string err;
    auto cube = parseCube(identityCube(3), &err);
    const int index = LookLibrary::instance().addCube("test_identity", "测试", cube);
    Look look;
    CHECK(LookLibrary::instance().get(index, &look));
    CHECK(look.isCube);
    // 709 信号 + 单位 .cube = 单位 LUT，顺带验证内存顺序（R 最快）
    const int n = 5;
    const std::vector<float> data = lut::bake(look, Encoding::Rec709, n);
    const size_t i = size_t(((3 * n + 1) * n + 2) * 4);   // r=2, g=1, b=3
    CHECK_NEAR(data[i], 0.5, 1e-4);
    CHECK_NEAR(data[i + 1], 0.25, 1e-4);
    CHECK_NEAR(data[i + 2], 0.75, 1e-4);
    // Log 信号会先被转成 709 再喂给 .cube
    const Vec3 out = lut::evaluate(look, Encoding::CineLog, Vec3(color::cineLogEncode(0.18f)));
    CHECK_NEAR(out.g, color::linearToSrgb(0.18f), 2e-3);
}

// ------------------------------------------------------------------ 几何

TEST(computeFrameCrops) {
    // 1920x1080 的传感器流，装成 90 度 → 直立 1080x1920
    FrameGeometry g = computeFrame(1920, 1080, 90, AspectRatio::R16x9);
    CHECK(g.frameWidth == 1080 && g.frameHeight == 1920);
    CHECK_NEAR(g.cropW, 1.0, 1e-6);
    g = computeFrame(1920, 1080, 90, AspectRatio::R4x3);
    CHECK(g.frameWidth == 1080 && g.frameHeight == 1440);
    CHECK_NEAR(g.cropH, 0.75, 1e-6);
    CHECK_NEAR(g.cropY, 0.125, 1e-6);
    g = computeFrame(1920, 1080, 90, AspectRatio::R239x1);
    CHECK(g.frameWidth == 800 && g.frameHeight == 1920);
    g = computeFrame(1440, 1080, 270, AspectRatio::R1x1);
    CHECK(g.frameWidth == 1080 && g.frameHeight == 1080);
    g = computeFrame(1440, 1080, 90, AspectRatio::R4x3);   // 4:3 流 + 4:3 画幅 = 全幅
    CHECK(g.frameWidth == 1080 && g.frameHeight == 1440);
}

TEST(cropMatrixMapsCorners) {
    FrameGeometry g = computeFrame(1920, 1080, 90, AspectRatio::R4x3);
    const Mat4 m = cropMatrix(g);
    float u, v;
    m.apply(0.f, 0.f, &u, &v);
    CHECK_NEAR(u, 0.0, 1e-6);
    CHECK_NEAR(v, 0.125, 1e-6);
    m.apply(1.f, 1.f, &u, &v);
    CHECK_NEAR(u, 1.0, 1e-6);
    CHECK_NEAR(v, 0.875, 1e-6);
}

TEST(matrixMultiplyComposes) {
    const Mat4 a = Mat4::affine(0.1f, 0.2f, 0.6f, 0.2f, 0.1f, 0.9f);
    const Mat4 b = Mat4::affine(0.f, 1.f, 1.f, 1.f, 0.f, 0.f);
    const Mat4 ab = a * b;
    float u1, v1, u2, v2;
    b.apply(0.3f, 0.7f, &u1, &v1);
    a.apply(u1, v1, &u1, &v1);
    ab.apply(0.3f, 0.7f, &u2, &v2);
    CHECK_NEAR(u1, u2, 1e-6);
    CHECK_NEAR(v1, v2, 1e-6);
}

TEST(stillInputMatrixRotates) {
    // 后置 90 度：直立画面的左上角，来自传感器位图的左下角（s=0, t=1）
    const Mat4 m = stillInputMatrix(90, false);
    float s, t;
    m.apply(0.f, 1.f, &s, &t);
    CHECK_NEAR(s, 0.0, 1e-6);
    CHECK_NEAR(t, 1.0, 1e-6);
    // 直立画面右上角 → 位图左上角
    m.apply(1.f, 1.f, &s, &t);
    CHECK_NEAR(s, 0.0, 1e-6);
    CHECK_NEAR(t, 0.0, 1e-6);
    // 前置 270 度 + 镜像：直立（镜像）画面左上角 → 位图右下角？逐步推：镜像后 ux = 1，uy = 0，
    // 逆时针还原 270：bx = 1 - uy = 1，by = ux = 1
    const Mat4 f = stillInputMatrix(270, true);
    f.apply(0.f, 1.f, &s, &t);
    CHECK_NEAR(s, 1.0, 1e-6);
    CHECK_NEAR(t, 1.0, 1e-6);
}

TEST(outputMatrixRotation) {
    // 输出 = 画面顺时针转 90：输出左下角显示的是画面右下角（uv = (1, 0)）
    const Mat4 m = outputMatrix(90, false, false);
    float u, v;
    m.apply(0.f, 0.f, &u, &v);
    CHECK_NEAR(u, 1.0, 1e-6);
    CHECK_NEAR(v, 0.0, 1e-6);
    // 不转 + flipY：输出 GL 第 0 行放画面顶行
    const Mat4 f = outputMatrix(0, false, true);
    f.apply(0.3f, 0.f, &u, &v);
    CHECK_NEAR(u, 0.3, 1e-6);
    CHECK_NEAR(v, 1.0, 1e-6);
    // 去镜像：左右互换
    const Mat4 mm = outputMatrix(0, true, false);
    mm.apply(0.2f, 0.4f, &u, &v);
    CHECK_NEAR(u, 0.8, 1e-6);
    CHECK_NEAR(v, 0.4, 1e-6);
}

TEST(videoAndPhotoPlans) {
    OutputPlan p = planVideoOutput(1080, 1920, 0);   // 竖拿
    CHECK(p.width == 1920 && p.height == 1080);
    CHECK(p.rotationCw == 90 && p.orientationHint == 270);
    p = planVideoOutput(1080, 1920, 270);            // 右侧朝上（逆时针横拿）
    CHECK(p.rotationCw == 90 && p.orientationHint == 0);
    p = planVideoOutput(1080, 1920, 90);             // 左侧朝上
    CHECK(p.rotationCw == 270 && p.orientationHint == 0);
    p = planVideoOutput(1080, 1920, 180);            // 倒拿
    CHECK(p.rotationCw == 90 && p.orientationHint == 90);
    p = planPhotoOutput(1080, 1440, 0);
    CHECK(p.rotationCw == 0 && p.width == 1080 && p.height == 1440);
    p = planPhotoOutput(1080, 1440, 90);
    CHECK(p.rotationCw == 270 && p.width == 1440 && p.height == 1080);
    CHECK(uprightRotation(359) == 0);
    CHECK(uprightRotation(80) == 270);
}

TEST(fitCenterLetterboxes) {
    const RectF r = fitCenter(1080.f, 1920.f, 1000.f, 1000.f);
    CHECK_NEAR(r.h, 1000.0, 1e-3);
    CHECK_NEAR(r.w, 562.5, 1e-3);
    CHECK_NEAR(r.x, 218.75, 1e-3);
    CHECK_NEAR(r.y, 0.0, 1e-3);
}

// ------------------------------------------------------------------ pass 规划 / shader

TEST(glowPlanIsResolutionIndependent) {
    const plan::GlowPlan preview = plan::planGlow(1080, 1920, plan::GlowKind::Halation);
    const plan::GlowPlan still = plan::planGlow(3000, 4000, plan::GlowKind::Halation);
    CHECK(preview.levels.size() == 4 && still.levels.size() == 4);
    CHECK(preview.levels[0].w == 540 && preview.levels[0].h == 960);
    CHECK(still.levels[0].w == 540 && still.levels[0].h == 720);
    const plan::GlowPlan mist = plan::planGlow(1080, 1920, plan::GlowKind::Mist);
    CHECK(mist.levels.size() == 6);
    CHECK(mist.levels.back().w == 16);
    CHECK_NEAR(plan::grainSizePixels(0.f, 3000, 4000) / plan::grainSizePixels(0.f, 1080, 1920), 3000.0 / 1080.0,
               1e-4);
}

TEST(waveformAndThumbnailPlans) {
    const plan::Size s = plan::waveformSourceSize(1080, 1920);
    CHECK(s.w == 192 && s.h == 256);
    const auto r = plan::waveformRect(1000.f, 1800.f);
    CHECK(r[2] > r[0] && r[3] > r[1] && r[2] <= 1.f && r[3] <= 1.f);
    const plan::Size atlas = plan::thumbnailAtlasSize(11, 120, 160, 4);
    CHECK(atlas.w == 480 && atlas.h == 480);
}

TEST(embeddedShadersResolveIncludes) {
    const char* names[] = {"quad.vert", "input_oes.frag", "input_2d.frag", "glow_prefilter.frag",
                           "kawase_down.frag", "kawase_up.frag", "film.frag", "present.frag",
                           "blit.frag", "waveform.vert", "waveform.frag"};
    for (const char* name : names) {
        const std::string src = shaders::load(name);
        CHECK(!src.empty());
        CHECK(src.rfind("#version 300 es", 0) == 0);   // 第一行必须是 #version
        CHECK(src.find("#include") == std::string::npos);
    }
    CHECK(shaders::load("film.frag").find("cineLogEncode") != std::string::npos);
}

int main() {
    for (const TestCase& t : registry()) {
        const int before = gFailures;
        t.body();
        std::printf("%s %s\n", gFailures == before ? "[ OK ]" : "[FAIL]", t.name);
    }
    std::printf("\n%d checks, %d failures\n", gChecks, gFailures);
    return gFailures == 0 ? 0 : 1;
}
