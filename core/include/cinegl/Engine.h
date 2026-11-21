// CineGL core — OpenGL ES 3.0 渲染引擎
//
// 头文件里不出现任何 GL 类型，JNI 层包含它不需要 GL 头。
// 所有方法都必须在「有当前 GL 上下文」的线程上调用；上下文、Surface 由平台层负责。
//
// 一帧的旅程：
//   相机纹理 ─[镜头 input]→ signal ─[光晕/柔光 glow]→ ─[胶片 film: LUT + 颗粒]→ graded
//   graded（或 signal）再分别画到：屏幕（present，带监看辅助）、编码器、拍照读回。
#pragma once

#include <cstdint>
#include <memory>

#include "cinegl/Color.h"
#include "cinegl/FrameGeometry.h"

namespace cinegl {

struct RenderParams {
    Encoding sourceEncoding = Encoding::Rec709;    // 相机给的信号（ISP 直出 Log 时为 CineLog）
    Encoding workingEncoding = Encoding::Rec709;   // 管线内部的信号（开 Log 时为 CineLog）
    float lookStrength = 1.f;   // 影调强度 0..1
    float halation = 0.f;       // 光晕 0..1
    float mist = 0.f;           // 柔光 0..1
    float grain = 0.f;          // 颗粒 0..1
    float grainSize = 0.3f;     // 颗粒大小 0..1
    float vignette = 0.f;       // 暗角 0..1
    float aberration = 0.f;     // 色散 0..1
    Vec3 wbGains{1.f, 1.f, 1.f};

    // —— 监看辅助（只影响屏幕）——
    bool showSignal = false;    // 直接看工作信号（开 Log 时就是「灰片」）
    bool zebra = false;
    float zebraThreshold = 0.95f;
    bool peaking = false;
    bool waveform = false;
};

struct InputFrame {
    unsigned texture = 0;
    bool externalOes = true;    // 相机预览：GL_TEXTURE_EXTERNAL_OES；拍照位图：GL_TEXTURE_2D
    Mat4 texMatrix;             // 画面 uv → 输入纹理坐标（已经包含裁切、旋转、相机矩阵）
};

struct PresentParams {
    int viewWidth = 0;          // 当前 surface 像素尺寸
    int viewHeight = 0;
    RectF frameRect;            // 画面在 surface 里的位置（左上原点，像素）
    float cornerRadius = 0.f;   // 圆角半径（像素）
};

enum class Plane { Signal = 0, Graded = 1 };

class Engine {
public:
    Engine();
    ~Engine();
    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;

    /** 编译 shader、建基础对象。失败返回 false（看日志）。 */
    bool initialize();
    /** 释放所有 GL 资源（必须在上下文还在时调用）。 */
    void release();

    // —— LUT ——
    void uploadNeutralLut(const float* rgba, int size);
    void uploadLookLut(int lookIndex, const float* rgba, int size);
    void clearLookLuts();
    bool hasLookLut(int lookIndex) const;
    void selectLook(int lookIndex);

    // —— 每帧 ——
    /** 输入 → signal → graded，结果留在引擎内部的帧缓冲里。 */
    void renderFrame(const InputFrame& input, int frameW, int frameH, const RenderParams& p, uint32_t frameIndex);
    /** 画到当前绑定的 surface（默认帧缓冲 0），带圆角和监看辅助。 */
    void present(const PresentParams& pp, const RenderParams& p, float timeSeconds);
    /** 把 signal / graded 按矩阵画满当前 surface（录像编码器用）。 */
    void drawToCurrentSurface(Plane plane, const Mat4& uvMatrix, int width, int height);

    // —— 离线 ——
    /**
     * 处理一张静态图（拍照）：用独立的一套帧缓冲跑完整管线，结果按 outMatrix 画到 outW x outH，
     * 读回到 dstRgba（RGBA8，outW * outH * 4 字节，顶行在前——outMatrix 记得带 flipY）。
     */
    bool processStill(const InputFrame& input, int frameW, int frameH, const RenderParams& p, Plane plane,
                      const Mat4& outMatrix, int outW, int outH, uint8_t* dstRgba);

    /**
     * 用最近一帧的 signal 给多个影调各渲染一张缩略图，拼成图集读回（顶行在前）。
     * dstRgba 大小 = plan::thumbnailAtlasSize(...) 的 w * h * 4。
     */
    bool renderThumbnails(const int* lookIndices, int count, int thumbW, int thumbH, int columns, uint8_t* dstRgba);

    int frameWidth() const;
    int frameHeight() const;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace cinegl
