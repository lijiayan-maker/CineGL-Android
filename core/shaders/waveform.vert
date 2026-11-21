#version 300 es
// ---- 波形图：用顶点着色器做「散点」----
// 不传任何顶点数据，glDrawArrays(GL_POINTS, 0, w * h) 画 w*h 个点；
// 每个点用 gl_VertexID 找到缩小画面里对应的像素，横坐标 = 像素所在列，纵坐标 = 它的亮度。
// 配合加法混合，同一位置落的点越多就越亮——这就是波形示波器。
precision highp float;

uniform highp sampler2D uSource;
uniform ivec2 uSourceSize;

void main() {
    int id = gl_VertexID;
    ivec2 p = ivec2(id % uSourceSize.x, id / uSourceSize.x);
    vec3 c = texelFetch(uSource, p, 0).rgb;
    float l = clamp(dot(c, vec3(0.2126, 0.7152, 0.0722)), 0.0, 1.0);
    float x = (float(p.x) + 0.5) / float(uSourceSize.x);
    gl_Position = vec4(x * 2.0 - 1.0, l * 1.96 - 0.98, 0.0, 1.0);
    gl_PointSize = 1.0;
}
