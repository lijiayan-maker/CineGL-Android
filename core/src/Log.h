// 极简日志：Android 走 logcat，主机单测走 stderr
#pragma once

#if defined(__ANDROID__)
#include <android/log.h>
#define CINE_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "CineGL", __VA_ARGS__)
#define CINE_LOGW(...) __android_log_print(ANDROID_LOG_WARN, "CineGL", __VA_ARGS__)
#define CINE_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "CineGL", __VA_ARGS__)
#else
#include <cstdio>
#define CINE_LOG_IMPL(level, ...)                     \
    do {                                              \
        std::fprintf(stderr, "[CineGL/" level "] ");  \
        std::fprintf(stderr, __VA_ARGS__);            \
        std::fprintf(stderr, "\n");                   \
    } while (0)
#define CINE_LOGI(...) CINE_LOG_IMPL("I", __VA_ARGS__)
#define CINE_LOGW(...) CINE_LOG_IMPL("W", __VA_ARGS__)
#define CINE_LOGE(...) CINE_LOG_IMPL("E", __VA_ARGS__)
#endif
