# CineGL core：渲染核心（C++，被 app 通过 JNI 调用）
#   cinegl_cpu   纯 C++（色彩、影调、LUT 烘焙、.cube、几何、pass 规划）——不依赖 Android，电脑上也能编
#   cinegl_gles  OpenGL ES 3.0 渲染引擎（Android NDK）
#   cinegl_tests 主机单测（macOS / Linux 直接跑，不需要手机）
cmake_minimum_required(VERSION 3.18)
project(cinegl_core LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(CINEGL_BUILD_GLES "Build the OpenGL ES 3.0 backend" ON)
option(CINEGL_BUILD_TESTS "Build host unit tests" OFF)

add_library(cinegl_cpu STATIC
    src/color/Color.cpp
    src/color/CubeLut.cpp
    src/color/Looks.cpp
    src/color/LutBaker.cpp
    src/geometry/FrameGeometry.cpp
    src/pipeline/PassPlan.cpp
)
target_include_directories(cinegl_cpu PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include
                                      PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)

# 着色器：一份 GLSL 源码，编译期嵌入
file(GLOB CINEGL_SHADER_FILES CONFIGURE_DEPENDS
    ${CMAKE_CURRENT_SOURCE_DIR}/shaders/*.glsl
    ${CMAKE_CURRENT_SOURCE_DIR}/shaders/*.vert
    ${CMAKE_CURRENT_SOURCE_DIR}/shaders/*.frag)
list(SORT CINEGL_SHADER_FILES)
include(${CMAKE_CURRENT_SOURCE_DIR}/cmake/EmbedShaders.cmake)
set(CINEGL_EMBEDDED_SHADERS ${CMAKE_CURRENT_BINARY_DIR}/generated/EmbeddedShaders.cpp)
cinegl_embed_shaders(${CINEGL_EMBEDDED_SHADERS} ${CINEGL_SHADER_FILES})

if(CINEGL_BUILD_GLES)
    add_library(cinegl_gles STATIC
        src/gl/GlObjects.cpp
        src/pipeline/ShaderLibrary.cpp
        src/pipeline/Engine.cpp
        ${CINEGL_EMBEDDED_SHADERS}
    )
    target_include_directories(cinegl_gles PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include
                                           PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)
    target_link_libraries(cinegl_gles PUBLIC cinegl_cpu)
    target_link_libraries(cinegl_gles PUBLIC GLESv3 log)
endif()

if(CINEGL_BUILD_TESTS)
    # 测试也会校验嵌入的 shader 都能找到、#include 都能展开
    add_executable(cinegl_tests
        tests/CoreTests.cpp
        src/pipeline/ShaderLibrary.cpp
        ${CINEGL_EMBEDDED_SHADERS})
    target_include_directories(cinegl_tests PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)
    target_link_libraries(cinegl_tests PRIVATE cinegl_cpu)
    enable_testing()
    add_test(NAME cinegl_tests COMMAND cinegl_tests)
endif()
