// CineGL core — 画面几何：裁切、旋转、镜像、输出方向
//
// 坐标约定（全项目统一，踩坑重灾区，务必先读）：
//   * 「画面（frame）」= 竖持手机时看到的直立画面，长边竖直。预览、录像、拍照都从它出发。
//     前置摄像头的画面是镜像的（像照镜子），和系统相机预览一致。
//   * 纹理坐标 uv 用 GL 习惯：(0,0) 在左下角，v 向上。
//   * 「图像坐标」(x, y) 用图片习惯：(0,0) 在左上角，y 向下。只在推导时用。
//   * 矩阵是 4x4 列主序（和 glUniformMatrix4fv、android.opengl.Matrix 一致），
//     作用在 vec4(u, v, 0, 1) 上，只用到其中的二维仿射部分。
#pragma once

#include <array>

namespace cinegl {

struct Mat4 {
    std::array<float, 16> m{};

    static Mat4 identity();
    /** 由一个二维仿射映射构造：f(0,0)、f(1,0)、f(0,1) 三个点就能确定它。 */
    static Mat4 affine(float ox, float oy, float ux, float uy, float vx, float vy);
    Mat4 operator*(const Mat4& o) const;
    /** 变换一个点 (u, v)。 */
    void apply(float u, float v, float* outU, float* outV) const;
};

/** 画幅：都按「长边 : 短边」描述。 */
enum class AspectRatio : int { R16x9 = 0, R4x3 = 1, R1x1 = 2, R239x1 = 3 };

float aspectLongOverShort(AspectRatio a);

struct FrameGeometry {
    int frameWidth = 0;    // 画面宽（像素，直立方向）
    int frameHeight = 0;   // 画面高
    // 在直立图像（未裁切）里的裁切区域，归一化，图像坐标（左上原点）
    float cropX = 0.f, cropY = 0.f, cropW = 1.f, cropH = 1.f;
};

/**
 * 根据相机输出缓冲尺寸（传感器方向，通常是横的）、传感器安装角度和画幅，算出画面尺寸和裁切区域。
 * 被裁的那条边向下取整到 align 的倍数（编码器要求偶数，8 的倍数兼容性最好；
 * 取 16 的话 1:1 会把 1080 裁成 1072），没被裁的边保持原样。
 */
FrameGeometry computeFrame(int bufferWidth, int bufferHeight, int sensorOrientation, AspectRatio aspect,
                           int align = 8);

/** 画面 uv → 直立图像 uv（GL 约定）。和 SurfaceTexture 的矩阵相乘：texMatrix = stMatrix * cropMatrix。 */
Mat4 cropMatrix(const FrameGeometry& g);

/**
 * 拍照用：直立画面 uv（前置时为镜像画面）→ 照片位图纹理坐标。
 * 位图是传感器方向的 JPEG 解码结果，上传成纹理后第 0 行（图片顶部）在 t = 0。
 */
Mat4 stillInputMatrix(int sensorOrientation, bool frontFacing);

/**
 * 输出用：输出目标的 uv → 画面 uv。
 *   rotationCw —— 输出图像相对画面顺时针转了多少度（0/90/180/270）
 *   unmirror   —— 前置摄像头：把镜像翻回来（保存的照片/视频是「别人眼中的你」）
 *   flipY      —— glReadPixels 是从底行开始读的；为了读回来就是「顶行在前」，绘制时上下翻转
 */
Mat4 outputMatrix(int rotationCw, bool unmirror, bool flipY);

struct OutputPlan {
    int width = 0;
    int height = 0;
    int rotationCw = 0;        // 画面 → 输出缓冲 的顺时针旋转
    int orientationHint = 0;   // 写进 MP4 的旋转元数据（播放器再顺时针转这么多）
};

/**
 * 把设备朝向（OrientationEventListener 的度数，吸附到 0/90/180/270）换算成：
 * 「画面要顺时针转多少度，才能在真实世界里是正的」。
 */
int uprightRotation(int deviceOrientationDeg);

/** 录像：编码缓冲永远是横的（宽 ≥ 高，编码器兼容性最好），竖拍时靠旋转元数据让播放器转回来。 */
OutputPlan planVideoOutput(int frameWidth, int frameHeight, int deviceOrientationDeg);

/** 拍照：直接把像素转正，不依赖 EXIF 方向。 */
OutputPlan planPhotoOutput(int frameWidth, int frameHeight, int deviceOrientationDeg);

struct RectF {
    float x = 0.f, y = 0.f, w = 0.f, h = 0.f;
};

/** 等比缩放后居中放进盒子（左上角原点）。UI 叠加层和 GL 预览都用它，保证两边算出同一个框。 */
RectF fitCenter(float contentW, float contentH, float boxW, float boxH);

}  // namespace cinegl
