#include "cinegl/CubeLut.h"

#include <cctype>
#include <cstdlib>
#include <sstream>

namespace cinegl {

namespace {

std::string trim(const std::string& s) {
    size_t b = 0, e = s.size();
    while (b < e && std::isspace(static_cast<unsigned char>(s[b]))) ++b;
    while (e > b && std::isspace(static_cast<unsigned char>(s[e - 1]))) --e;
    return s.substr(b, e - b);
}

bool parseFloats(const std::string& s, float* out, int n) {
    const char* p = s.c_str();
    for (int i = 0; i < n; ++i) {
        char* end = nullptr;
        out[i] = std::strtof(p, &end);
        if (end == p) return false;
        p = end;
    }
    return true;
}

void setError(CubeParseError* error, CubeError code, int line = 0, int count = 0, int expected = 0) {
    if (error) *error = CubeParseError{code, line, count, expected};
}

}  // namespace

std::string CubeParseError::describe() const {
    const std::string at = "line " + std::to_string(line) + ": ";
    switch (code) {
        case CubeError::None: return "ok";
        case CubeError::SizeOutOfRange: return "LUT_3D_SIZE out of range (2..256)";
        case CubeError::Unsupported1D: return "1D LUTs are not supported";
        case CubeError::BadDomain: return at + "DOMAIN needs 3 numbers";
        case CubeError::BadData: return at + "expected 3 numbers";
        case CubeError::DataBeforeSize: return at + "data before LUT_3D_SIZE";
        case CubeError::CountMismatch:
            return "found " + std::to_string(count) + " data lines, LUT_3D_SIZE needs " + std::to_string(expected);
        case CubeError::DomainOrder: return "DOMAIN_MAX must be greater than DOMAIN_MIN";
        case CubeError::MissingSize: return "LUT_3D_SIZE is missing";
    }
    return "unknown error";
}

Vec3 CubeLut::sample(const Vec3& in) const {
    const Vec3 range = domainMax - domainMin;
    const float n = static_cast<float>(size - 1);
    const float fr = clamp01((in.r - domainMin.r) / range.r) * n;
    const float fg = clamp01((in.g - domainMin.g) / range.g) * n;
    const float fb = clamp01((in.b - domainMin.b) / range.b) * n;
    const int r0 = std::min(static_cast<int>(fr), size - 2);
    const int g0 = std::min(static_cast<int>(fg), size - 2);
    const int b0 = std::min(static_cast<int>(fb), size - 2);
    const float tr = fr - r0, tg = fg - g0, tb = fb - b0;

    // 三线性：先沿 R 插 4 次，再沿 G 插 2 次，最后沿 B 插 1 次
    const Vec3 c00 = mix(at(r0, g0, b0), at(r0 + 1, g0, b0), tr);
    const Vec3 c10 = mix(at(r0, g0 + 1, b0), at(r0 + 1, g0 + 1, b0), tr);
    const Vec3 c01 = mix(at(r0, g0, b0 + 1), at(r0 + 1, g0, b0 + 1), tr);
    const Vec3 c11 = mix(at(r0, g0 + 1, b0 + 1), at(r0 + 1, g0 + 1, b0 + 1), tr);
    return mix(mix(c00, c10, tg), mix(c01, c11, tg), tb);
}

std::shared_ptr<CubeLut> parseCube(const std::string& text, CubeParseError* error) {
    auto lut = std::make_shared<CubeLut>();
    std::istringstream stream(text);
    std::string raw;
    int lineNo = 0;
    while (std::getline(stream, raw)) {
        ++lineNo;
        std::string line = trim(raw);
        if (line.empty() || line[0] == '#') continue;

        if (std::isalpha(static_cast<unsigned char>(line[0]))) {
            std::istringstream ls(line);
            std::string key;
            ls >> key;
            const std::string rest = trim(line.substr(key.size()));
            if (key == "TITLE") {
                std::string t = rest;
                if (t.size() >= 2 && t.front() == '"' && t.back() == '"') t = t.substr(1, t.size() - 2);
                lut->title = t;
            } else if (key == "LUT_3D_SIZE") {
                lut->size = std::atoi(rest.c_str());
                if (lut->size < 2 || lut->size > 256) {
                    setError(error, CubeError::SizeOutOfRange, lineNo);
                    return nullptr;
                }
                lut->data.reserve(static_cast<size_t>(lut->size) * lut->size * lut->size);
            } else if (key == "LUT_1D_SIZE") {
                setError(error, CubeError::Unsupported1D, lineNo);
                return nullptr;
            } else if (key == "DOMAIN_MIN" || key == "DOMAIN_MAX") {
                float v[3];
                if (!parseFloats(rest, v, 3)) {
                    setError(error, CubeError::BadDomain, lineNo);
                    return nullptr;
                }
                (key == "DOMAIN_MIN" ? lut->domainMin : lut->domainMax) = Vec3(v[0], v[1], v[2]);
            }
            // 其他关键字（LUT_IN_VIDEO_RANGE 等）忽略
            continue;
        }

        float v[3];
        if (!parseFloats(line, v, 3)) {
            setError(error, CubeError::BadData, lineNo);
            return nullptr;
        }
        if (lut->size == 0) {
            setError(error, CubeError::DataBeforeSize, lineNo);
            return nullptr;
        }
        lut->data.emplace_back(v[0], v[1], v[2]);
    }

    if (lut->size == 0) {
        setError(error, CubeError::MissingSize);
        return nullptr;
    }
    const size_t expected = static_cast<size_t>(lut->size) * lut->size * lut->size;
    if (lut->data.size() != expected) {
        setError(error, CubeError::CountMismatch, 0, static_cast<int>(lut->data.size()), static_cast<int>(expected));
        return nullptr;
    }
    const Vec3 range = lut->domainMax - lut->domainMin;
    if (range.r <= 0.f || range.g <= 0.f || range.b <= 0.f) {
        setError(error, CubeError::DomainOrder);
        return nullptr;
    }
    return lut;
}

}  // namespace cinegl
