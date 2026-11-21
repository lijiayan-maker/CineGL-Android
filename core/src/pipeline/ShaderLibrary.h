// shader 源码库：源码在编译期被 CMake 嵌进二进制（见 cmake/EmbedShaders.cmake），
// 运行时不读文件，不用操心 assets 路径，主机单测也能直接校验。
#pragma once

#include <string>

namespace cinegl::shaders {

/** 生成文件里提供：按文件名找嵌入的源码，找不到返回 nullptr。 */
const char* findEmbedded(const std::string& name);

/** 取出源码并展开其中的 #include "xxx"（递归）。找不到时返回空串。 */
std::string load(const std::string& name);

}  // namespace cinegl::shaders
