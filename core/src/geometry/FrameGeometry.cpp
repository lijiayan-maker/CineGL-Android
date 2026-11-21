#include "cinegl/FrameGeometry.h"

#include <algorithm>
#include <cmath>

namespace cinegl {

Mat4 Mat4::identity() {
    Mat4 r;
    r.m = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    return r;
}

Mat4 Mat4::affine(float ox, float oy, float ux, float uy, float vx, float vy) {
    // out = o + u_axis * u + v_axis * v，其中 u_axis = f(1,0) - f(0,0)，v_axis = f(0,1) - f(0,0)
    Mat4 r = identity();
    r.m[0] = ux - ox;
    r.m[1] = uy - oy;
    r.m[4] = vx - ox;
    r.m[5] = vy - oy;
    r.m[12] = ox;
    r.m[13] = oy;
    return r;
}

Mat4 Mat4::operator*(const Mat4& o) const {
    Mat4 r;
    for (int col = 0; col < 4; ++col) {
        for (int row = 0; row < 4; ++row) {
            float s = 0.f;
            for (int k = 0; k < 4; ++k) s += m[k * 4 + row] * o.m[col * 4 + k];
            r.m[col * 4 + row] = s;
        }
    }
    return r;
}

void Mat4::apply(float u, float v, float* outU, float* outV) const {
    *outU = m[0] * u + m[4] * v + m[12];
    *outV = m[1] * u + m[5] * v + m[13];
}

namespace {

int normalizeDegrees(int deg) {
    deg %= 360;
    if (deg < 0) deg += 360;
    return (deg + 45) / 90 * 90 % 360;   // 吸附到 0/90/180/270
}

/**
 * 「顺时针旋转 deg 度」的逆映射：已知旋转后图像里的点 (x, y)，求它在旋转前图像里的位置。
 * 都是图像坐标（左上原点、归一化）。推导：顺时针转 90° 时，原图左上角 (0,0) 跑到右上角 (1,0)，
 * 原图左下角 (0,1) 跑到左上角 (0,0)，于是 src = (y, 1 - x)。
 */
void unrotate(int deg, float x, float y, float* sx, float* sy) {
    switch (deg) {
        case 90: *sx = y; *sy = 1.f - x; break;
        case 180: *sx = 1.f - x; *sy = 1.f - y; break;
        case 270: *sx = 1.f - y; *sy = x; break;
        default: *sx = x; *sy = y; break;
    }
}

template <typename F>
Mat4 affineFrom(F&& f) {
    float ox, oy, ux, uy, vx, vy;
    f(0.f, 0.f, &ox, &oy);
    f(1.f, 0.f, &ux, &uy);
    f(0.f, 1.f, &vx, &vy);
    return Mat4::affine(ox, oy, ux, uy, vx, vy);
}

int alignDown(int v, int align) { return std::max(align, v / align * align); }

}  // namespace

float aspectLongOverShort(AspectRatio a) {
    switch (a) {
        case AspectRatio::R16x9: return 16.f / 9.f;
        case AspectRatio::R4x3: return 4.f / 3.f;
        case AspectRatio::R1x1: return 1.f;
        case AspectRatio::R239x1: return 2.39f;
    }
    return 16.f / 9.f;
}

FrameGeometry computeFrame(int bufferWidth, int bufferHeight, int sensorOrientation, AspectRatio aspect, int align) {
    const bool swap = normalizeDegrees(sensorOrientation) % 180 == 90;
    // 直立图像尺寸：传感器横放、装成 90/270 度时宽高互换，得到竖直的长方形
    int uprightW = swap ? bufferHeight : bufferWidth;
    int uprightH = swap ? bufferWidth : bufferHeight;
    // 画面长边沿手机长轴（竖直）：目标宽高比 = 短 / 长
    const bool portrait = uprightH >= uprightW;
    const float target = portrait ? 1.f / aspectLongOverShort(aspect) : aspectLongOverShort(aspect);

    FrameGeometry g;
    const float current = static_cast<float>(uprightW) / static_cast<float>(uprightH);
    int w = uprightW, h = uprightH;
    // 比例本来就一样（比如 16:9 的流配 16:9 画幅）就别裁，避免浮点误差 + 对齐白白裁掉 8 个像素
    if (std::fabs(current - target) > 1e-3f) {
        if (current > target) {
            w = alignDown(static_cast<int>(std::lround(uprightH * target)), align);   // 太宽：裁左右
        } else {
            h = alignDown(static_cast<int>(std::lround(uprightW / target)), align);   // 太高：裁上下
        }
    }
    g.frameWidth = w;
    g.frameHeight = h;
    g.cropW = static_cast<float>(w) / static_cast<float>(uprightW);
    g.cropH = static_cast<float>(h) / static_cast<float>(uprightH);
    g.cropX = (1.f - g.cropW) * 0.5f;
    g.cropY = (1.f - g.cropH) * 0.5f;
    return g;
}

Mat4 cropMatrix(const FrameGeometry& g) {
    // 图像坐标的 cropY 从上往下量；换成 GL 的 v（从下往上）是 1 - cropY - cropH。居中裁切时两者相等。
    const float v0 = 1.f - g.cropY - g.cropH;
    return Mat4::affine(g.cropX, v0, g.cropX + g.cropW, v0, g.cropX, v0 + g.cropH);
}

Mat4 stillInputMatrix(int sensorOrientation, bool frontFacing) {
    const int deg = normalizeDegrees(sensorOrientation);
    return affineFrom([&](float u, float v, float* s, float* t) {
        // 画面 uv → 画面图像坐标
        float px = u, py = 1.f - v;
        // 前置的画面是镜像的，先翻回「真实直立图像」
        float ux = frontFacing ? 1.f - px : px;
        float uy = py;
        // 直立图像 = 位图顺时针转 deg → 反推位图坐标
        float bx, by;
        unrotate(deg, ux, uy, &bx, &by);
        // 位图第 0 行在 t = 0，所以 t 直接等于图像坐标 y
        *s = bx;
        *t = by;
    });
}

Mat4 outputMatrix(int rotationCw, bool unmirror, bool flipY) {
    const int deg = normalizeDegrees(rotationCw);
    return affineFrom([&](float ou, float ov, float* u, float* v) {
        // 输出 uv → 输出图像坐标（flipY 时让图像顶部落在 GL 的第 0 行）
        float ox = ou;
        float oy = flipY ? ov : 1.f - ov;
        // 输出图像 = 去镜像后的画面顺时针转 deg → 反推
        float gx, gy;
        unrotate(deg, ox, oy, &gx, &gy);
        float fx = unmirror ? 1.f - gx : gx;
        float fy = gy;
        *u = fx;
        *v = 1.f - fy;
    });
}

int uprightRotation(int deviceOrientationDeg) {
    // 设备顺时针转了 d 度（左侧朝上 = 90），画面跟着顺时针歪了 d 度，要逆时针转回来 = 顺时针 360 - d
    return (360 - normalizeDegrees(deviceOrientationDeg)) % 360;
}

OutputPlan planVideoOutput(int frameWidth, int frameHeight, int deviceOrientationDeg) {
    const int upright = uprightRotation(deviceOrientationDeg);
    OutputPlan p;
    if (upright == 90 || upright == 270) {
        // 横拿手机：直接把画面转正，缓冲本身就是横的，不需要旋转元数据
        p.rotationCw = upright;
        p.orientationHint = 0;
    } else {
        // 竖拿：缓冲仍然做成横的（画面顺时针转 90），再让播放器补转回来
        p.rotationCw = 90;
        p.orientationHint = (upright - 90 + 360) % 360;
    }
    // 旋转 90/270 后宽高互换
    p.width = frameHeight;
    p.height = frameWidth;
    return p;
}

OutputPlan planPhotoOutput(int frameWidth, int frameHeight, int deviceOrientationDeg) {
    OutputPlan p;
    p.rotationCw = uprightRotation(deviceOrientationDeg);
    const bool swap = p.rotationCw % 180 == 90;
    p.width = swap ? frameHeight : frameWidth;
    p.height = swap ? frameWidth : frameHeight;
    p.orientationHint = 0;
    return p;
}

RectF fitCenter(float contentW, float contentH, float boxW, float boxH) {
    RectF r;
    if (contentW <= 0.f || contentH <= 0.f || boxW <= 0.f || boxH <= 0.f) return r;
    const float scale = std::min(boxW / contentW, boxH / contentH);
    r.w = contentW * scale;
    r.h = contentH * scale;
    r.x = (boxW - r.w) * 0.5f;
    r.y = (boxH - r.h) * 0.5f;
    return r;
}

}  // namespace cinegl
