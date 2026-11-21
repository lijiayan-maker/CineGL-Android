// ---- 公共函数：信号编码（和 core/src/color/Color.cpp 保持完全一致）----
// 编码 0 = Rec.709 / sRGB 显示编码；1 = CineLog
const float CINELOG_K = 63.0;
const float CINELOG_LOG2_RANGE = 7.982993575;   // log2(1 + 63 * 4)

vec3 srgbToLinear(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    vec3 lo = c / 12.92;
    vec3 hi = pow((c + 0.055) / 1.055, vec3(2.4));
    return mix(lo, hi, step(vec3(0.04045), c));
}

vec3 linearToSrgb(vec3 x) {
    x = max(x, vec3(0.0));
    vec3 lo = x * 12.92;
    vec3 hi = 1.055 * pow(x, vec3(1.0 / 2.4)) - 0.055;
    return mix(lo, hi, step(vec3(0.0031308), x));
}

vec3 cineLogEncode(vec3 x) {
    return log2(1.0 + CINELOG_K * max(x, vec3(0.0))) / CINELOG_LOG2_RANGE;
}

vec3 cineLogDecode(vec3 y) {
    return (exp2(y * CINELOG_LOG2_RANGE) - 1.0) / CINELOG_K;
}

vec3 decodeSignal(vec3 s, int encoding) {
    return encoding == 1 ? cineLogDecode(s) : srgbToLinear(s);
}

vec3 encodeSignal(vec3 x, int encoding) {
    return encoding == 1 ? cineLogEncode(x) : linearToSrgb(x);
}

float luma709(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}
