// 对 GL 对象的薄封装：程序、帧缓冲、全屏四边形、3D LUT 纹理。
// 约定：所有方法都必须在有 GL 上下文的线程调用；析构函数不碰 GL（上下文可能已经没了），
// 资源要显式 release()。
#pragma once

#include <string>
#include <unordered_map>

#include "gl/GlApi.h"

namespace cinegl::gl {

void checkError(const char* where);
bool hasExtension(const char* name);

class Program {
public:
    bool build(const std::string& vertexSrc, const std::string& fragmentSrc, const std::string& name);
    void release();
    bool valid() const { return id_ != 0; }
    void use() const;

    GLint location(const char* uniform);
    void setInt(const char* n, int v);
    void setFloat(const char* n, float v);
    void setVec2(const char* n, float x, float y);
    void setVec3(const char* n, float x, float y, float z);
    void setVec4(const char* n, float x, float y, float z, float w);
    void setIVec2(const char* n, int x, int y);
    void setMat4(const char* n, const float* m);
    /** 把纹理绑到第 unit 个纹理单元，并把 sampler uniform 指过去。 */
    void bindTexture(const char* sampler, int unit, GLenum target, GLuint texture);

private:
    GLuint id_ = 0;
    std::string name_;
    std::unordered_map<std::string, GLint> locations_;
};

enum class TexFormat { RGBA8, RGBA16F };

class Framebuffer {
public:
    bool create(int width, int height, TexFormat format);
    void release();
    bool valid() const { return fbo_ != 0; }
    /** 绑定为渲染目标，并把视口设成整张纹理。 */
    void bind() const;

    GLuint texture() const { return texture_; }
    int width() const { return width_; }
    int height() const { return height_; }
    TexFormat format() const { return format_; }

private:
    GLuint fbo_ = 0;
    GLuint texture_ = 0;
    int width_ = 0;
    int height_ = 0;
    TexFormat format_ = TexFormat::RGBA8;
};

/** 一个覆盖整个视口的四边形（两个三角形，TRIANGLE_STRIP）。 */
class Quad {
public:
    bool create();
    void release();
    void draw() const;

private:
    GLuint vao_ = 0;
    GLuint vbo_ = 0;
};

/** 3D LUT 纹理：RGBA16F，三线性过滤。 */
class Lut3D {
public:
    bool upload(const float* rgba, int size);
    void release();
    bool valid() const { return texture_ != 0; }
    GLuint texture() const { return texture_; }
    int size() const { return size_; }

private:
    GLuint texture_ = 0;
    int size_ = 0;
};

}  // namespace cinegl::gl
