#version 300 es
// 纯拷贝：配合 uTexMatrix 完成缩放、旋转、镜像（录像、拍照读回、缩略图源）
precision highp float;

in highp vec2 vUv;
uniform highp sampler2D uInput;
out vec4 fragColor;

void main() {
    fragColor = vec4(texture(uInput, vUv).rgb, 1.0);
}
