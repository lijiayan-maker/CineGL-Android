// JNI 桥：Kotlin 的 com.mycro.cinegl.core.CineCore ↔ C++ core。
// 这里只做类型转换，不放任何业务逻辑——逻辑都在 core 里（能在电脑上单测）。
#include <android/log.h>
#include <jni.h>

#include <cmath>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "cinegl/Color.h"
#include "cinegl/CubeLut.h"
#include "cinegl/Engine.h"
#include "cinegl/FrameGeometry.h"
#include "cinegl/Looks.h"
#include "cinegl/LutBaker.h"
#include "cinegl/PassPlan.h"

using namespace cinegl;

namespace {

std::mutex gErrorMutex;
CubeParseError gLastCubeError;

void setLastCubeError(const CubeParseError& e) {
    std::lock_guard<std::mutex> lock(gErrorMutex);
    gLastCubeError = e;
}

std::string toString(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

jfloatArray toJava(JNIEnv* env, const float* data, size_t n) {
    jfloatArray arr = env->NewFloatArray(static_cast<jsize>(n));
    env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(n), data);
    return arr;
}

jfloatArray toJava(JNIEnv* env, const Mat4& m) { return toJava(env, m.m.data(), 16); }

Mat4 toMat4(JNIEnv* env, jfloatArray arr) {
    Mat4 m = Mat4::identity();
    if (arr && env->GetArrayLength(arr) >= 16) env->GetFloatArrayRegion(arr, 0, 16, m.m.data());
    return m;
}

Engine* engine(jlong handle) { return reinterpret_cast<Engine*>(handle); }

// RenderParams 在 Kotlin 侧打包成 FloatArray，下标和 CineCore.kt 里的 RenderParams.pack() 一一对应
constexpr int kParamCount = 17;

RenderParams unpack(JNIEnv* env, jfloatArray arr) {
    float v[kParamCount] = {};
    if (arr && env->GetArrayLength(arr) >= kParamCount) env->GetFloatArrayRegion(arr, 0, kParamCount, v);
    RenderParams p;
    p.sourceEncoding = static_cast<Encoding>(static_cast<int>(v[0]));
    p.workingEncoding = static_cast<Encoding>(static_cast<int>(v[1]));
    p.lookStrength = v[2];
    p.halation = v[3];
    p.mist = v[4];
    p.grain = v[5];
    p.grainSize = v[6];
    p.vignette = v[7];
    p.aberration = v[8];
    p.wbGains = {v[9], v[10], v[11]};
    p.showSignal = v[12] > 0.5f;
    p.zebra = v[13] > 0.5f;
    p.zebraThreshold = v[14];
    p.peaking = v[15] > 0.5f;
    p.waveform = v[16] > 0.5f;
    return p;
}

}  // namespace

#define JNI_FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_mycro_cinegl_core_CineCore_##name

// ================================================================ 影调库（纯 CPU，任意线程）

JNI_FN(jint, lookCount)(JNIEnv*, jobject) { return LookLibrary::instance().count(); }

JNI_FN(jobjectArray, lookInfo)(JNIEnv* env, jobject, jint index) {
    Look look;
    if (!LookLibrary::instance().get(index, &look)) return nullptr;
    jobjectArray arr = env->NewObjectArray(4, env->FindClass("java/lang/String"), nullptr);
    env->SetObjectArrayElement(arr, 0, env->NewStringUTF(look.id.c_str()));
    env->SetObjectArrayElement(arr, 1, env->NewStringUTF(look.name.c_str()));
    env->SetObjectArrayElement(arr, 2, env->NewStringUTF(look.nameEn.c_str()));
    env->SetObjectArrayElement(arr, 3, env->NewStringUTF(look.isCube ? "1" : "0"));
    return arr;
}

JNI_FN(jfloatArray, bakeLook)(JNIEnv* env, jobject, jint index, jint encoding, jint size) {
    Look look;
    if (!LookLibrary::instance().get(index, &look)) return nullptr;
    const std::vector<float> data = lut::bake(look, static_cast<Encoding>(encoding), size);
    return toJava(env, data.data(), data.size());
}

JNI_FN(jfloatArray, bakeNeutral)(JNIEnv* env, jobject, jint encoding, jint size) {
    const std::vector<float> data = lut::bakeNeutral(static_cast<Encoding>(encoding), size);
    return toJava(env, data.data(), data.size());
}

JNI_FN(jint, registerCubeLook)(JNIEnv* env, jobject, jstring id, jstring name, jstring text) {
    CubeParseError error;
    auto cube = parseCube(toString(env, text), &error);
    if (!cube) {
        __android_log_print(ANDROID_LOG_WARN, "CineGL", "cube rejected: %s", error.describe().c_str());
        setLastCubeError(error);
        return -1;
    }
    return LookLibrary::instance().addCube(toString(env, id), toString(env, name), cube);
}

/** [错误码, 行号, 实际行数, 应有行数]，文案由 Kotlin 按系统语言生成。 */
JNI_FN(jintArray, lastCubeError)(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(gErrorMutex);
    const jint out[] = {static_cast<jint>(gLastCubeError.code), gLastCubeError.line, gLastCubeError.count,
                        gLastCubeError.expected};
    jintArray arr = env->NewIntArray(4);
    env->SetIntArrayRegion(arr, 0, 4, out);
    return arr;
}

// ================================================================ 几何 / 色彩工具（纯 CPU）

JNI_FN(jfloatArray, computeFrame)(JNIEnv* env, jobject, jint bufW, jint bufH, jint sensor, jint aspect) {
    const FrameGeometry g = computeFrame(bufW, bufH, sensor, static_cast<AspectRatio>(aspect));
    const float out[] = {static_cast<float>(g.frameWidth), static_cast<float>(g.frameHeight), g.cropX, g.cropY,
                         g.cropW, g.cropH};
    return toJava(env, out, 6);
}

JNI_FN(jfloatArray, cropMatrix)(JNIEnv* env, jobject, jfloatArray geometry) {
    float v[6] = {};
    env->GetFloatArrayRegion(geometry, 0, 6, v);
    FrameGeometry g;
    g.frameWidth = static_cast<int>(v[0]);
    g.frameHeight = static_cast<int>(v[1]);
    g.cropX = v[2];
    g.cropY = v[3];
    g.cropW = v[4];
    g.cropH = v[5];
    return toJava(env, cinegl::cropMatrix(g));
}

JNI_FN(jfloatArray, stillInputMatrix)(JNIEnv* env, jobject, jint sensor, jboolean front) {
    return toJava(env, cinegl::stillInputMatrix(sensor, front));
}

JNI_FN(jfloatArray, outputMatrix)(JNIEnv* env, jobject, jint rotation, jboolean unmirror, jboolean flipY) {
    return toJava(env, cinegl::outputMatrix(rotation, unmirror, flipY));
}

JNI_FN(jintArray, planVideoOutput)(JNIEnv* env, jobject, jint fw, jint fh, jint deviceDeg) {
    const OutputPlan p = cinegl::planVideoOutput(fw, fh, deviceDeg);
    const jint out[] = {p.width, p.height, p.rotationCw, p.orientationHint};
    jintArray arr = env->NewIntArray(4);
    env->SetIntArrayRegion(arr, 0, 4, out);
    return arr;
}

JNI_FN(jintArray, planPhotoOutput)(JNIEnv* env, jobject, jint fw, jint fh, jint deviceDeg) {
    const OutputPlan p = cinegl::planPhotoOutput(fw, fh, deviceDeg);
    const jint out[] = {p.width, p.height, p.rotationCw, p.orientationHint};
    jintArray arr = env->NewIntArray(4);
    env->SetIntArrayRegion(arr, 0, 4, out);
    return arr;
}

JNI_FN(jfloatArray, whiteBalanceGains)(JNIEnv* env, jobject, jfloat kelvin, jfloat tint) {
    const Vec3 g = color::whiteBalanceGains(kelvin, tint);
    const float out[] = {g.r, g.g, g.b};
    return toJava(env, out, 3);
}

JNI_FN(jfloatArray, cineLogCurve)(JNIEnv* env, jobject, jint points) {
    // 给 Camera2 TONEMAP_CURVE 用：(Pin, Pout) 交错。Pin 按对数间隔取点，暗部更密
    std::vector<float> out;
    out.reserve(static_cast<size_t>(points) * 2);
    for (int i = 0; i < points; ++i) {
        const float t = static_cast<float>(i) / static_cast<float>(points - 1);
        const float x = (std::exp2(t * 8.f) - 1.f) / 255.f;
        out.push_back(x);
        out.push_back(color::cineLogEncode(x));
    }
    return toJava(env, out.data(), out.size());
}

// ================================================================ GL 引擎（必须在 GL 线程）

JNI_FN(jlong, engineCreate)(JNIEnv*, jobject) { return reinterpret_cast<jlong>(new Engine()); }

JNI_FN(jboolean, engineInit)(JNIEnv*, jobject, jlong h) { return engine(h)->initialize(); }

JNI_FN(void, engineRelease)(JNIEnv*, jobject, jlong h) {
    Engine* e = engine(h);
    e->release();
    delete e;
}

JNI_FN(void, engineUploadNeutralLut)(JNIEnv* env, jobject, jlong h, jfloatArray data, jint size) {
    jfloat* p = env->GetFloatArrayElements(data, nullptr);
    engine(h)->uploadNeutralLut(p, size);
    env->ReleaseFloatArrayElements(data, p, JNI_ABORT);
}

JNI_FN(void, engineUploadLookLut)(JNIEnv* env, jobject, jlong h, jint index, jfloatArray data, jint size) {
    jfloat* p = env->GetFloatArrayElements(data, nullptr);
    engine(h)->uploadLookLut(index, p, size);
    env->ReleaseFloatArrayElements(data, p, JNI_ABORT);
}

JNI_FN(void, engineClearLookLuts)(JNIEnv*, jobject, jlong h) { engine(h)->clearLookLuts(); }

JNI_FN(void, engineSelectLook)(JNIEnv*, jobject, jlong h, jint index) { engine(h)->selectLook(index); }

JNI_FN(void, engineRenderFrame)
(JNIEnv* env, jobject, jlong h, jint texture, jboolean oes, jfloatArray texMatrix, jint fw, jint fh,
 jfloatArray params, jint frameIndex) {
    InputFrame in;
    in.texture = static_cast<unsigned>(texture);
    in.externalOes = oes;
    in.texMatrix = toMat4(env, texMatrix);
    engine(h)->renderFrame(in, fw, fh, unpack(env, params), static_cast<uint32_t>(frameIndex));
}

JNI_FN(void, enginePresent)
(JNIEnv* env, jobject, jlong h, jint viewW, jint viewH, jfloat x, jfloat y, jfloat w, jfloat hh, jfloat radius,
 jfloat topInset, jfloatArray params, jfloat time) {
    PresentParams pp;
    pp.viewWidth = viewW;
    pp.viewHeight = viewH;
    pp.frameRect = {x, y, w, hh};
    pp.cornerRadius = radius;
    pp.overlayTopInset = topInset;
    engine(h)->present(pp, unpack(env, params), time);
}

JNI_FN(void, engineDrawToSurface)
(JNIEnv* env, jobject, jlong h, jint plane, jfloatArray matrix, jint w, jint hh) {
    engine(h)->drawToCurrentSurface(static_cast<Plane>(plane), toMat4(env, matrix), w, hh);
}

JNI_FN(jboolean, engineProcessStill)
(JNIEnv* env, jobject, jlong h, jint texture, jfloatArray texMatrix, jint fw, jint fh, jfloatArray params, jint plane,
 jfloatArray outMatrix, jint outW, jint outH, jobject dst) {
    auto* bytes = static_cast<uint8_t*>(env->GetDirectBufferAddress(dst));
    if (!bytes || env->GetDirectBufferCapacity(dst) < static_cast<jlong>(outW) * outH * 4) return JNI_FALSE;
    InputFrame in;
    in.texture = static_cast<unsigned>(texture);
    in.externalOes = false;
    in.texMatrix = toMat4(env, texMatrix);
    return engine(h)->processStill(in, fw, fh, unpack(env, params), static_cast<Plane>(plane), toMat4(env, outMatrix),
                                   outW, outH, bytes);
}

JNI_FN(jboolean, engineRenderThumbnails)
(JNIEnv* env, jobject, jlong h, jintArray lookIndices, jint thumbW, jint thumbH, jint columns, jobject dst) {
    auto* bytes = static_cast<uint8_t*>(env->GetDirectBufferAddress(dst));
    const jsize count = env->GetArrayLength(lookIndices);
    const plan::Size atlas = plan::thumbnailAtlasSize(count, thumbW, thumbH, columns);
    if (!bytes || env->GetDirectBufferCapacity(dst) < static_cast<jlong>(atlas.w) * atlas.h * 4) return JNI_FALSE;
    std::vector<int> indices(static_cast<size_t>(count));
    env->GetIntArrayRegion(lookIndices, 0, count, reinterpret_cast<jint*>(indices.data()));
    return engine(h)->renderThumbnails(indices.data(), count, thumbW, thumbH, columns, bytes);
}
