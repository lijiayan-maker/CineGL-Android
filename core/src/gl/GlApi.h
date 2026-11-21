// OpenGL ES 3.0 头文件。core 里所有 GL 代码只 include 这一个文件。
#pragma once

#if defined(__ANDROID__)
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#else
#error "CineGL 的 GL 层只在 Android（NDK 的 GLES3 头文件）下编译；主机单测只编纯 CPU 部分"
#endif

#ifndef GL_TEXTURE_EXTERNAL_OES
#define GL_TEXTURE_EXTERNAL_OES 0x8D65
#endif
