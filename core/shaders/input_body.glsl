// ---- 第 1 站「镜头」：取景裁切 + 色散 + 白平衡 + 暗角，输出工作信号 ----
// 被 input_oes.frag / input_2d.frag 包含，uInput 的类型由它们声明。
in highp vec2 vFrameUv;

uniform highp mat4 uTexMatrix;   // 画面 uv → 输入纹理坐标（必须和 quad.vert 里的声明一模一样）
uniform int uSourceEncoding;     // 输入信号编码：相机直出 = 0，ISP 直出 Log = 1
uniform int uWorkingEncoding;    // 管线内部信号编码：开 Log = 1
uniform vec3 uWbGains;           // GPU 白平衡增益（线性空间）
uniform float uVignette;         // 暗角 0..1
uniform float uAberration;       // 色散 0..1
uniform float uFrameAspect;      // 画面宽 / 高

out vec4 fragColor;

vec3 sampleInput(vec2 frameUv) {
    vec2 uv = (uTexMatrix * vec4(frameUv, 0.0, 1.0)).xy;
    return texture(uInput, uv).rgb;
}

void main() {
    vec2 p = vFrameUv - 0.5;
    // 换算成「物理比例」：x 乘上宽高比，这样暗角是圆的，不会被画幅拉成椭圆
    vec2 q = p * vec2(uFrameAspect, 1.0);
    float r = length(q) / length(vec2(0.5 * uFrameAspect, 0.5));   // 0 = 中心，1 = 四角

    vec3 sig;
    if (uAberration > 0.0) {
        // 横向色差：不同波长的放大率略有不同，R、B 两个通道沿径向往相反方向错开，越靠边越明显
        vec2 dir = p * r * (uAberration * 0.012);
        sig.r = sampleInput(vFrameUv - dir).r;
        sig.g = sampleInput(vFrameUv).g;
        sig.b = sampleInput(vFrameUv + dir).b;
    } else {
        sig = sampleInput(vFrameUv);
    }

    // 白平衡、暗角都是「光量」的乘法，必须在线性空间做
    vec3 lin = decodeSignal(sig, uSourceEncoding) * uWbGains;

    if (uVignette > 0.0) {
        // 自然暗角的 cos⁴ 定律：tanθ = r·k，亮度 ∝ cos⁴θ = 1 / (1 + (r·k)²)²
        float k = 1.4 * uVignette;
        float t = 1.0 / (1.0 + r * r * k * k);
        lin *= t * t;
    }
    fragColor = vec4(encodeSignal(lin, uWorkingEncoding), 1.0);
}
