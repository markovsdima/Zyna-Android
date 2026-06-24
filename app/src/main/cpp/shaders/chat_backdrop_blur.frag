#version 450

layout(set = 0, binding = 0) uniform sampler2D sourceTex;

layout(push_constant) uniform PushConstants {
    vec2 texelStep;
} pc;

layout(location = 0) in vec2 fragUv;
layout(location = 0) out vec4 outColor;

const float BLUR_RADIUS_SCALE = 1.85;

void main() {
    vec2 uv = clamp(fragUv, vec2(0.0), vec2(1.0));
    vec2 step = pc.texelStep * BLUR_RADIUS_SCALE;
    vec4 color = texture(sourceTex, uv) * 0.22702703;
    color += texture(sourceTex, uv + step * 1.38461538) * 0.31621622;
    color += texture(sourceTex, uv - step * 1.38461538) * 0.31621622;
    color += texture(sourceTex, uv + step * 3.23076923) * 0.07027027;
    color += texture(sourceTex, uv - step * 3.23076923) * 0.07027027;
    outColor = color;
}
