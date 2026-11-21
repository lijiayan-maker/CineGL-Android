#include "cinegl/Engine.h"

#include <unordered_map>
#include <vector>

#include "Log.h"
#include "cinegl/PassPlan.h"
#include "gl/GlObjects.h"
#include "pipeline/ShaderLibrary.h"

namespace cinegl {

using gl::Framebuffer;
using gl::Program;
using gl::TexFormat;

namespace {

// 纹理单元分配：同一个单元上不能同时挂 2D 和 3D 纹理给不同类型的 sampler，所以各占一个
constexpr int kUnitInput = 0;
constexpr int kUnitHalation = 1;
constexpr int kUnitMist = 2;
constexpr int kUnitNeutralLut = 3;
constexpr int kUnitLookLut = 4;
constexpr int kUnitWaveform = 1;

/** 一条模糊金字塔（光晕或柔光各一条）。 */
struct GlowChain {
    std::vector<Framebuffer> levels;

    bool ensure(const plan::GlowPlan& p, TexFormat* format) {
        bool same = levels.size() == p.levels.size();
        for (size_t i = 0; same && i < levels.size(); ++i) {
            same = levels[i].width() == p.levels[i].w && levels[i].height() == p.levels[i].h;
        }
        if (same) return true;
        release();
        for (const plan::Size& s : p.levels) {
            Framebuffer fb;
            if (!fb.create(s.w, s.h, *format)) {
                // 半精度渲染目标不可用：退回 RGBA8（亮部会被截到 1，光晕稍弱但能用）
                if (*format == TexFormat::RGBA16F && fb.create(s.w, s.h, TexFormat::RGBA8)) {
                    CINE_LOGW("RGBA16F render target unavailable, glow falls back to RGBA8");
                    *format = TexFormat::RGBA8;
                } else {
                    release();
                    return false;
                }
            }
            levels.push_back(fb);
        }
        return true;
    }

    void release() {
        for (Framebuffer& fb : levels) fb.release();
        levels.clear();
    }
};

/** 一套完整的帧缓冲：预览用一套常驻的，拍照时临时建一套大的。 */
struct FrameTargets {
    Framebuffer signal;   // 镜头之后的工作信号
    Framebuffer graded;   // 调色之后的成片
    GlowChain halation;
    GlowChain mist;

    bool ensure(int w, int h) {
        if (signal.valid() && signal.width() == w && signal.height() == h) return true;
        release();
        return signal.create(w, h, TexFormat::RGBA8) && graded.create(w, h, TexFormat::RGBA8);
    }

    void release() {
        signal.release();
        graded.release();
        halation.release();
        mist.release();
    }

    bool valid() const { return signal.valid() && graded.valid(); }
};

bool ensureFb(Framebuffer& fb, int w, int h, TexFormat format = TexFormat::RGBA8) {
    if (fb.valid() && fb.width() == w && fb.height() == h) return true;
    return fb.create(w, h, format);
}

const Mat4 kFlipV = Mat4::affine(0.f, 1.f, 1.f, 1.f, 0.f, 0.f);   // (u, v) → (u, 1 - v)

}  // namespace

struct Engine::Impl {
    bool ready = false;
    gl::Quad quad;
    GLuint emptyVao = 0;
    std::string quadVs;

    Program input2d, inputOes, prefilter, kawaseDown, kawaseUp, film, presentProg, blit, waveform;
    bool oesBuilt = false;
    TexFormat glowFormat = TexFormat::RGBA8;

    gl::Lut3D neutral;
    std::unordered_map<int, gl::Lut3D> looks;
    int selectedLook = -1;

    FrameTargets preview;
    RenderParams lastParams;
    Framebuffer waveformSource, waveformTarget;
    Framebuffer thumbSource, thumbAtlas;

    bool buildProgram(Program& p, const char* vs, const char* fs) {
        const std::string vsSrc = vs ? shaders::load(vs) : quadVs;
        const std::string fsSrc = shaders::load(fs);
        if (vsSrc.empty() || fsSrc.empty()) return false;
        return p.build(vsSrc, fsSrc, fs);
    }

    Program* inputProgram(bool oes) {
        if (!oes) return &input2d;
        if (!oesBuilt) {
            // 外部纹理（OES）程序用到时才编译：只拍照不预览的场景用不到它
            oesBuilt = true;
            if (!buildProgram(inputOes, nullptr, "input_oes.frag")) CINE_LOGE("input_oes program unavailable");
        }
        return inputOes.valid() ? &inputOes : nullptr;
    }

    const gl::Lut3D* lookLut() const {
        auto it = looks.find(selectedLook);
        return it != looks.end() && it->second.valid() ? &it->second : nullptr;
    }

    // ------------------------------------------------------------ passes

    /** 第 1 站：镜头。 */
    bool runInput(const InputFrame& in, int fw, int fh, const RenderParams& p, FrameTargets& t) {
        Program* prog = inputProgram(in.externalOes);
        if (!prog) return false;
        t.signal.bind();
        prog->use();
        prog->bindTexture("uInput", kUnitInput, in.externalOes ? GL_TEXTURE_EXTERNAL_OES : GL_TEXTURE_2D, in.texture);
        prog->setMat4("uTexMatrix", in.texMatrix.m.data());
        prog->setInt("uSourceEncoding", static_cast<int>(p.sourceEncoding));
        prog->setInt("uWorkingEncoding", static_cast<int>(p.workingEncoding));
        prog->setVec3("uWbGains", p.wbGains.r, p.wbGains.g, p.wbGains.b);
        prog->setFloat("uVignette", p.vignette);
        prog->setFloat("uAberration", p.aberration);
        prog->setFloat("uFrameAspect", static_cast<float>(fw) / static_cast<float>(fh));
        quad.draw();
        return true;
    }

    /** 光晕/柔光：提取高光 → Dual Kawase 降采样 → 升采样，返回 level 0 的纹理。 */
    GLuint runGlow(GlowChain& chain, const Framebuffer& src, Encoding enc, plan::GlowKind kind) {
        const plan::GlowPlan gp = plan::planGlow(src.width(), src.height(), kind);
        if (!chain.ensure(gp, &glowFormat)) return 0;
        const Mat4 identity = Mat4::identity();

        chain.levels[0].bind();
        prefilter.use();
        prefilter.bindTexture("uInput", kUnitInput, GL_TEXTURE_2D, src.texture());
        prefilter.setMat4("uTexMatrix", identity.m.data());
        prefilter.setInt("uWorkingEncoding", static_cast<int>(enc));
        prefilter.setVec2("uTapOffset", gp.tapOffsetU, gp.tapOffsetV);
        prefilter.setFloat("uThreshold", gp.threshold);
        prefilter.setFloat("uKnee", gp.knee);
        quad.draw();

        const size_t n = chain.levels.size();
        kawaseDown.use();
        kawaseDown.setMat4("uTexMatrix", identity.m.data());
        kawaseDown.setFloat("uOffset", 1.f);
        for (size_t i = 1; i < n; ++i) {
            const Framebuffer& from = chain.levels[i - 1];
            chain.levels[i].bind();
            kawaseDown.bindTexture("uInput", kUnitInput, GL_TEXTURE_2D, from.texture());
            kawaseDown.setVec2("uHalfTexel", 0.5f / from.width(), 0.5f / from.height());
            quad.draw();
        }
        // 升采样直接写回上一级：那一级的降采样结果已经用完了，省一套显存
        kawaseUp.use();
        kawaseUp.setMat4("uTexMatrix", identity.m.data());
        kawaseUp.setFloat("uOffset", 1.f);
        for (size_t i = n - 1; i-- > 0;) {
            const Framebuffer& from = chain.levels[i + 1];
            chain.levels[i].bind();
            kawaseUp.bindTexture("uInput", kUnitInput, GL_TEXTURE_2D, from.texture());
            kawaseUp.setVec2("uHalfTexel", 0.5f / from.width(), 0.5f / from.height());
            quad.draw();
        }
        return chain.levels[0].texture();
    }

    /** 第 2、3 站：胶片曝光 + 冲洗，写进 target。 */
    void runFilm(GLuint signalTex, GLuint halationTex, GLuint mistTex, const Framebuffer& target, int fw, int fh,
                 const RenderParams& p, uint32_t seed, const gl::Lut3D* look, float lookStrength, const Mat4& uvMatrix,
                 bool effects) {
        film.use();
        film.setMat4("uTexMatrix", uvMatrix.m.data());
        film.bindTexture("uInput", kUnitInput, GL_TEXTURE_2D, signalTex);
        film.bindTexture("uHalationTex", kUnitHalation, GL_TEXTURE_2D, halationTex ? halationTex : signalTex);
        film.bindTexture("uMistTex", kUnitMist, GL_TEXTURE_2D, mistTex ? mistTex : signalTex);
        film.bindTexture("uNeutralLut", kUnitNeutralLut, GL_TEXTURE_3D, neutral.texture());
        film.setFloat("uNeutralLutSize", static_cast<float>(neutral.size()));
        const gl::Lut3D* lut = look ? look : &neutral;
        film.bindTexture("uLookLut", kUnitLookLut, GL_TEXTURE_3D, lut->texture());
        film.setFloat("uLookLutSize", static_cast<float>(lut->size()));
        film.setFloat("uLookStrength", look ? lookStrength : 0.f);
        film.setInt("uWorkingEncoding", static_cast<int>(p.workingEncoding));
        film.setFloat("uHalationAmount", effects && halationTex ? p.halation : 0.f);
        film.setFloat("uMistAmount", effects && mistTex ? p.mist : 0.f);
        film.setFloat("uGrain", effects ? p.grain : 0.f);
        film.setFloat("uGrainSize", plan::grainSizePixels(p.grainSize, fw, fh));
        film.setVec2("uFrameSize", static_cast<float>(fw), static_cast<float>(fh));
        film.setFloat("uSeed", static_cast<float>(seed % 65536u));
        (void)target;
        quad.draw();
    }

    bool runPipeline(const InputFrame& in, int fw, int fh, const RenderParams& p, uint32_t seed, FrameTargets& t) {
        if (!t.ensure(fw, fh)) return false;
        glDisable(GL_BLEND);
        glDisable(GL_DEPTH_TEST);
        if (!runInput(in, fw, fh, p, t)) return false;

        GLuint halationTex = 0, mistTex = 0;
        if (p.halation > 0.f) halationTex = runGlow(t.halation, t.signal, p.workingEncoding, plan::GlowKind::Halation);
        if (p.mist > 0.f) mistTex = runGlow(t.mist, t.signal, p.workingEncoding, plan::GlowKind::Mist);

        t.graded.bind();
        runFilm(t.signal.texture(), halationTex, mistTex, t.graded, fw, fh, p, seed, lookLut(), p.lookStrength,
                Mat4::identity(), true);
        gl::checkError("runPipeline");
        return true;
    }

    void blitInto(const Framebuffer& target, GLuint tex, const Mat4& m) {
        target.bind();
        blit.use();
        blit.bindTexture("uInput", kUnitInput, GL_TEXTURE_2D, tex);
        blit.setMat4("uTexMatrix", m.m.data());
        quad.draw();
    }

    /** 波形图：缩小画面 → 每个像素一个点，加法混合累加。 */
    GLuint updateWaveform(GLuint src, int fw, int fh) {
        const plan::Size s = plan::waveformSourceSize(fw, fh);
        if (!ensureFb(waveformSource, s.w, s.h) ||
            !ensureFb(waveformTarget, plan::kWaveformTarget.w, plan::kWaveformTarget.h)) {
            return 0;
        }
        blitInto(waveformSource, src, Mat4::identity());

        waveformTarget.bind();
        glClearColor(0.f, 0.f, 0.f, 1.f);
        glClear(GL_COLOR_BUFFER_BIT);
        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE);
        waveform.use();
        waveform.bindTexture("uSource", kUnitInput, GL_TEXTURE_2D, waveformSource.texture());
        waveform.setIVec2("uSourceSize", s.w, s.h);
        // 每列有 s.h 个点落在 128 行里，单点贡献要小，靠叠加变亮
        waveform.setVec3("uColor", 0.035f, 0.07f, 0.045f);
        glBindVertexArray(emptyVao);
        glDrawArrays(GL_POINTS, 0, s.w * s.h);
        glBindVertexArray(0);
        glDisable(GL_BLEND);
        return waveformTarget.texture();
    }
};

Engine::Engine() : impl_(std::make_unique<Impl>()) {}

Engine::~Engine() = default;

bool Engine::initialize() {
    Impl& d = *impl_;
    if (d.ready) return true;
    const char* version = reinterpret_cast<const char*>(glGetString(GL_VERSION));
    const char* renderer = reinterpret_cast<const char*>(glGetString(GL_RENDERER));
    CINE_LOGI("GL_VERSION=%s, GL_RENDERER=%s", version ? version : "?", renderer ? renderer : "?");

    d.quadVs = shaders::load("quad.vert");
    bool ok = !d.quadVs.empty() && d.quad.create();
    ok = ok && d.buildProgram(d.input2d, nullptr, "input_2d.frag");
    ok = ok && d.buildProgram(d.prefilter, nullptr, "glow_prefilter.frag");
    ok = ok && d.buildProgram(d.kawaseDown, nullptr, "kawase_down.frag");
    ok = ok && d.buildProgram(d.kawaseUp, nullptr, "kawase_up.frag");
    ok = ok && d.buildProgram(d.film, nullptr, "film.frag");
    ok = ok && d.buildProgram(d.presentProg, nullptr, "present.frag");
    ok = ok && d.buildProgram(d.blit, nullptr, "blit.frag");
    ok = ok && d.buildProgram(d.waveform, "waveform.vert", "waveform.frag");
    if (!ok) {
        CINE_LOGE("engine initialize failed");
        release();
        return false;
    }
    glGenVertexArrays(1, &d.emptyVao);

    const bool halfFloatTarget =
        gl::hasExtension("GL_EXT_color_buffer_half_float") || gl::hasExtension("GL_EXT_color_buffer_float");
    d.glowFormat = halfFloatTarget ? TexFormat::RGBA16F : TexFormat::RGBA8;
    CINE_LOGI("glow buffers: %s", halfFloatTarget ? "RGBA16F" : "RGBA8");

    // 先放一张 2³ 的单位 LUT 兜底，真正的中性 LUT 烘好之前也能出图
    const float identity[] = {0, 0, 0, 1, 1, 0, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1,
                              0, 0, 1, 1, 1, 0, 1, 1, 0, 1, 1, 1, 1, 1, 1, 1};
    d.neutral.upload(identity, 2);
    d.ready = true;
    gl::checkError("Engine::initialize");
    return true;
}

void Engine::release() {
    Impl& d = *impl_;
    for (Program* p : {&d.input2d, &d.inputOes, &d.prefilter, &d.kawaseDown, &d.kawaseUp, &d.film, &d.presentProg,
                       &d.blit, &d.waveform}) {
        p->release();
    }
    d.oesBuilt = false;
    d.quad.release();
    if (d.emptyVao) glDeleteVertexArrays(1, &d.emptyVao);
    d.emptyVao = 0;
    d.neutral.release();
    clearLookLuts();
    d.preview.release();
    d.waveformSource.release();
    d.waveformTarget.release();
    d.thumbSource.release();
    d.thumbAtlas.release();
    d.ready = false;
}

void Engine::uploadNeutralLut(const float* rgba, int size) { impl_->neutral.upload(rgba, size); }

void Engine::uploadLookLut(int lookIndex, const float* rgba, int size) { impl_->looks[lookIndex].upload(rgba, size); }

void Engine::clearLookLuts() {
    for (auto& entry : impl_->looks) entry.second.release();
    impl_->looks.clear();
}

bool Engine::hasLookLut(int lookIndex) const {
    auto it = impl_->looks.find(lookIndex);
    return it != impl_->looks.end() && it->second.valid();
}

void Engine::selectLook(int lookIndex) { impl_->selectedLook = lookIndex; }

void Engine::renderFrame(const InputFrame& input, int frameW, int frameH, const RenderParams& p, uint32_t frameIndex) {
    Impl& d = *impl_;
    if (!d.ready || frameW <= 0 || frameH <= 0) return;
    d.lastParams = p;
    d.runPipeline(input, frameW, frameH, p, frameIndex, d.preview);
}

void Engine::present(const PresentParams& pp, const RenderParams& p, float timeSeconds) {
    Impl& d = *impl_;
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, pp.viewWidth, pp.viewHeight);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
    if (!d.ready || !d.preview.valid()) return;

    const int fw = d.preview.signal.width();
    const int fh = d.preview.signal.height();
    const GLuint src = p.showSignal ? d.preview.signal.texture() : d.preview.graded.texture();
    const GLuint wave = p.waveform ? d.updateWaveform(src, fw, fh) : 0;

    // 视口：UI 给的是左上原点，GL 的视口是左下原点
    const int x = static_cast<int>(pp.frameRect.x + 0.5f);
    const int w = static_cast<int>(pp.frameRect.w + 0.5f);
    const int h = static_cast<int>(pp.frameRect.h + 0.5f);
    const int y = pp.viewHeight - (static_cast<int>(pp.frameRect.y + 0.5f) + h);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(x, y, w, h);

    Program& prog = d.presentProg;
    prog.use();
    prog.setMat4("uTexMatrix", Mat4::identity().m.data());
    prog.bindTexture("uInput", kUnitInput, GL_TEXTURE_2D, src);
    prog.bindTexture("uWaveform", kUnitWaveform, GL_TEXTURE_2D, wave ? wave : src);
    prog.setVec2("uTexel", 1.f / fw, 1.f / fh);
    prog.setVec2("uViewport", static_cast<float>(w), static_cast<float>(h));
    prog.setFloat("uCornerRadius", pp.cornerRadius);
    prog.setInt("uZebra", p.zebra ? 1 : 0);
    prog.setFloat("uZebraThreshold", p.zebraThreshold);
    prog.setInt("uPeaking", p.peaking ? 1 : 0);
    prog.setFloat("uTime", timeSeconds);
    const auto rect = wave ? plan::waveformRect(static_cast<float>(w), static_cast<float>(h), pp.overlayTopInset)
                           : std::array<float, 4>{0.f, 0.f, 0.f, 0.f};
    prog.setVec4("uWaveformRect", rect[0], rect[1], rect[2], rect[3]);
    d.quad.draw();
    gl::checkError("Engine::present");
}

void Engine::drawToCurrentSurface(Plane plane, const Mat4& uvMatrix, int width, int height) {
    Impl& d = *impl_;
    if (!d.ready || !d.preview.valid()) return;
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, width, height);
    glDisable(GL_BLEND);
    d.blit.use();
    d.blit.bindTexture("uInput", kUnitInput, GL_TEXTURE_2D,
                       plane == Plane::Signal ? d.preview.signal.texture() : d.preview.graded.texture());
    d.blit.setMat4("uTexMatrix", uvMatrix.m.data());
    d.quad.draw();
}

bool Engine::processStill(const InputFrame& input, int frameW, int frameH, const RenderParams& p, Plane plane,
                          const Mat4& outMatrix, int outW, int outH, uint8_t* dstRgba) {
    Impl& d = *impl_;
    if (!d.ready || !dstRgba) return false;
    GLint maxSize = 0;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxSize);
    if (frameW > maxSize || frameH > maxSize || outW > maxSize || outH > maxSize) {
        CINE_LOGE("still %dx%d exceeds GL_MAX_TEXTURE_SIZE %d", frameW, frameH, maxSize);
        return false;
    }

    FrameTargets t;
    Framebuffer out;
    bool ok = d.runPipeline(input, frameW, frameH, p, 7u, t) && out.create(outW, outH, TexFormat::RGBA8);
    if (ok) {
        d.blitInto(out, plane == Plane::Signal ? t.signal.texture() : t.graded.texture(), outMatrix);
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadPixels(0, 0, outW, outH, GL_RGBA, GL_UNSIGNED_BYTE, dstRgba);
        ok = glGetError() == GL_NO_ERROR;
    }
    out.release();
    t.release();
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    return ok;
}

bool Engine::renderThumbnails(const int* lookIndices, int count, int thumbW, int thumbH, int columns,
                              uint8_t* dstRgba) {
    Impl& d = *impl_;
    if (!d.ready || !d.preview.valid() || count <= 0 || !dstRgba) return false;
    const plan::Size atlas = plan::thumbnailAtlasSize(count, thumbW, thumbH, columns);
    if (!ensureFb(d.thumbSource, thumbW, thumbH) || !ensureFb(d.thumbAtlas, atlas.w, atlas.h)) return false;

    glDisable(GL_BLEND);
    d.blitInto(d.thumbSource, d.preview.signal.texture(), Mat4::identity());

    d.thumbAtlas.bind();
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
    RenderParams p = d.lastParams;
    for (int i = 0; i < count; ++i) {
        const int col = i % columns;
        const int row = i / columns;
        glViewport(col * thumbW, row * thumbH, thumbW, thumbH);
        auto it = d.looks.find(lookIndices[i]);
        const gl::Lut3D* lut = it != d.looks.end() && it->second.valid() ? &it->second : nullptr;
        // kFlipV：让每格图片的顶部落在 GL 的低行，读回来就是「顶行在前」
        d.runFilm(d.thumbSource.texture(), 0, 0, d.thumbAtlas, thumbW, thumbH, p, 0u, lut, 1.f, kFlipV, false);
    }
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(0, 0, atlas.w, atlas.h, GL_RGBA, GL_UNSIGNED_BYTE, dstRgba);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    return glGetError() == GL_NO_ERROR;
}

int Engine::frameWidth() const { return impl_->preview.signal.width(); }
int Engine::frameHeight() const { return impl_->preview.signal.height(); }

}  // namespace cinegl
