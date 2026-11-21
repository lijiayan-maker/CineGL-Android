#include "gl/GlObjects.h"

#include <cstring>
#include <vector>

#include "Log.h"

namespace cinegl::gl {

void checkError(const char* where) {
    for (GLenum e = glGetError(); e != GL_NO_ERROR; e = glGetError()) {
        CINE_LOGE("GL error 0x%04x at %s", e, where);
    }
}

bool hasExtension(const char* name) {
    GLint count = 0;
    glGetIntegerv(GL_NUM_EXTENSIONS, &count);
    for (GLint i = 0; i < count; ++i) {
        const char* ext = reinterpret_cast<const char*>(glGetStringi(GL_EXTENSIONS, static_cast<GLuint>(i)));
        if (ext && std::strcmp(ext, name) == 0) return true;
    }
    return false;
}

// ---------------------------------------------------------------- Program

namespace {
GLuint compile(GLenum type, const std::string& src, const std::string& name) {
    GLuint shader = glCreateShader(type);
    const char* p = src.c_str();
    glShaderSource(shader, 1, &p, nullptr);
    glCompileShader(shader);
    GLint ok = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        GLint len = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &len);
        std::vector<char> log(static_cast<size_t>(std::max(len, 1)));
        glGetShaderInfoLog(shader, len, nullptr, log.data());
        CINE_LOGE("compile %s (%s) failed:\n%s", name.c_str(), type == GL_VERTEX_SHADER ? "vs" : "fs", log.data());
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}
}  // namespace

bool Program::build(const std::string& vertexSrc, const std::string& fragmentSrc, const std::string& name) {
    release();
    name_ = name;
    GLuint vs = compile(GL_VERTEX_SHADER, vertexSrc, name);
    GLuint fs = compile(GL_FRAGMENT_SHADER, fragmentSrc, name);
    if (!vs || !fs) {
        if (vs) glDeleteShader(vs);
        if (fs) glDeleteShader(fs);
        return false;
    }
    GLuint program = glCreateProgram();
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glLinkProgram(program);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint ok = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &ok);
    if (!ok) {
        GLint len = 0;
        glGetProgramiv(program, GL_INFO_LOG_LENGTH, &len);
        std::vector<char> log(static_cast<size_t>(std::max(len, 1)));
        glGetProgramInfoLog(program, len, nullptr, log.data());
        CINE_LOGE("link %s failed:\n%s", name.c_str(), log.data());
        glDeleteProgram(program);
        return false;
    }
    id_ = program;
    return true;
}

void Program::release() {
    if (id_) glDeleteProgram(id_);
    id_ = 0;
    locations_.clear();
}

void Program::use() const { glUseProgram(id_); }

GLint Program::location(const char* uniform) {
    auto it = locations_.find(uniform);
    if (it != locations_.end()) return it->second;
    GLint loc = glGetUniformLocation(id_, uniform);
    locations_.emplace(uniform, loc);
    return loc;
}

// location 为 -1（uniform 被编译器优化掉了）时，glUniform* 按规范是静默无操作
void Program::setInt(const char* n, int v) { glUniform1i(location(n), v); }
void Program::setFloat(const char* n, float v) { glUniform1f(location(n), v); }
void Program::setVec2(const char* n, float x, float y) { glUniform2f(location(n), x, y); }
void Program::setVec3(const char* n, float x, float y, float z) { glUniform3f(location(n), x, y, z); }
void Program::setVec4(const char* n, float x, float y, float z, float w) { glUniform4f(location(n), x, y, z, w); }
void Program::setIVec2(const char* n, int x, int y) { glUniform2i(location(n), x, y); }
void Program::setMat4(const char* n, const float* m) { glUniformMatrix4fv(location(n), 1, GL_FALSE, m); }

void Program::bindTexture(const char* sampler, int unit, GLenum target, GLuint texture) {
    glActiveTexture(GL_TEXTURE0 + unit);
    glBindTexture(target, texture);
    setInt(sampler, unit);
}

// ---------------------------------------------------------------- Framebuffer

bool Framebuffer::create(int width, int height, TexFormat format) {
    release();
    width_ = width;
    height_ = height;
    format_ = format;

    glGenTextures(1, &texture_);
    glBindTexture(GL_TEXTURE_2D, texture_);
    if (format == TexFormat::RGBA16F) {
        // 半精度浮点：能存 > 1 的值，适合线性空间的光晕；但做渲染目标需要 EXT_color_buffer_(half_)float
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA16F, width, height);
    } else {
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, width, height);
    }
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glGenFramebuffers(1, &fbo_);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture_, 0);
    const GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        CINE_LOGE("framebuffer %dx%d incomplete: 0x%04x", width, height, status);
        release();
        return false;
    }
    return true;
}

void Framebuffer::release() {
    if (fbo_) glDeleteFramebuffers(1, &fbo_);
    if (texture_) glDeleteTextures(1, &texture_);
    fbo_ = 0;
    texture_ = 0;
    width_ = height_ = 0;
}

void Framebuffer::bind() const {
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_);
    glViewport(0, 0, width_, height_);
}

// ---------------------------------------------------------------- Quad

bool Quad::create() {
    // 每个顶点：位置 xy（裁剪空间）+ 纹理坐标 uv
    static const float kVertices[] = {
        -1.f, -1.f, 0.f, 0.f,
         1.f, -1.f, 1.f, 0.f,
        -1.f,  1.f, 0.f, 1.f,
         1.f,  1.f, 1.f, 1.f,
    };
    glGenVertexArrays(1, &vao_);
    glGenBuffers(1, &vbo_);
    glBindVertexArray(vao_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(kVertices), kVertices, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), reinterpret_cast<void*>(0));
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), reinterpret_cast<void*>(2 * sizeof(float)));
    glBindVertexArray(0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    return vao_ != 0;
}

void Quad::release() {
    if (vbo_) glDeleteBuffers(1, &vbo_);
    if (vao_) glDeleteVertexArrays(1, &vao_);
    vbo_ = vao_ = 0;
}

void Quad::draw() const {
    glBindVertexArray(vao_);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);
}

// ---------------------------------------------------------------- Lut3D

bool Lut3D::upload(const float* rgba, int size) {
    if (!texture_ || size != size_) {
        release();
        glGenTextures(1, &texture_);
        glBindTexture(GL_TEXTURE_3D, texture_);
        glTexStorage3D(GL_TEXTURE_3D, 1, GL_RGBA16F, size, size, size);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
        size_ = size;
    } else {
        glBindTexture(GL_TEXTURE_3D, texture_);
    }
    // 数据是 float，内部格式是 RGBA16F：ES 3.0 允许这个组合，驱动负责转换
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glTexSubImage3D(GL_TEXTURE_3D, 0, 0, 0, 0, size, size, size, GL_RGBA, GL_FLOAT, rgba);
    glBindTexture(GL_TEXTURE_3D, 0);
    checkError("Lut3D::upload");
    return true;
}

void Lut3D::release() {
    if (texture_) glDeleteTextures(1, &texture_);
    texture_ = 0;
    size_ = 0;
}

}  // namespace cinegl::gl
