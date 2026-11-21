#version 300 es
// Android 相机输入：SurfaceTexture 给的是「外部纹理」，只能用 samplerExternalOES 采样，
// 它背后可能是 YUV 的 GraphicBuffer，YUV → RGB 由驱动在采样时顺手做掉。
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform highp samplerExternalOES uInput;

#include "common.glsl"
#include "input_body.glsl"
