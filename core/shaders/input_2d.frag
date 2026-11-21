#version 300 es
// 普通 2D 纹理输入：拍照时的高分辨率位图
precision highp float;
uniform highp sampler2D uInput;

#include "common.glsl"
#include "input_body.glsl"
