# Android 壳的原生部分：把跨平台 core 编进来，再加一层 JNI 桥
cmake_minimum_required(VERSION 3.22.1)
project(cinegl_jni LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../core ${CMAKE_CURRENT_BINARY_DIR}/cinegl_core)

add_library(cinegl_jni SHARED cinegl_jni.cpp)
target_compile_options(cinegl_jni PRIVATE -Wall -Wextra)
target_link_libraries(cinegl_jni PRIVATE cinegl_gles log)
