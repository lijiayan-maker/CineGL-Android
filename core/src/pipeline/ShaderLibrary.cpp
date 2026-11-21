#include "pipeline/ShaderLibrary.h"

#include <sstream>

#include "Log.h"

namespace cinegl::shaders {

namespace {
std::string expand(const std::string& name, int depth) {
    if (depth > 8) {
        CINE_LOGE("shader include too deep: %s", name.c_str());
        return {};
    }
    const char* src = findEmbedded(name);
    if (!src) {
        CINE_LOGE("shader not found: %s", name.c_str());
        return {};
    }
    std::istringstream in(src);
    std::ostringstream out;
    std::string line;
    while (std::getline(in, line)) {
        const auto pos = line.find("#include");
        if (pos != std::string::npos && line.find_first_not_of(" \t") == pos) {
            const auto q1 = line.find('"', pos);
            const auto q2 = q1 == std::string::npos ? q1 : line.find('"', q1 + 1);
            if (q2 != std::string::npos) {
                out << expand(line.substr(q1 + 1, q2 - q1 - 1), depth + 1) << '\n';
                continue;
            }
        }
        out << line << '\n';
    }
    return out.str();
}
}  // namespace

std::string load(const std::string& name) { return expand(name, 0); }

}  // namespace cinegl::shaders
