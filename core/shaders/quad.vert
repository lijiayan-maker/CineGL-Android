#version 300 es
// 全屏四边形：所有「一个像素算一个像素」的 pass 都用它
layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexCoord;

// 对纹理坐标做的变换：相机矩阵、裁切、旋转、镜像……全塞在这一个矩阵里
uniform highp mat4 uTexMatrix;

out highp vec2 vUv;        // 变换后的坐标：用来采样输入纹理
out highp vec2 vFrameUv;   // 原始 0..1 坐标：用来做「和画面位置有关」的计算（暗角、颗粒）

void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
    vUv = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
    vFrameUv = aTexCoord;
}
