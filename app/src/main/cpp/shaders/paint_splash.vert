#version 450

layout(push_constant) uniform PushConstants {
    vec2 viewportSize;
} pc;

layout(location = 0) in vec2 inPosition;
layout(location = 1) in vec2 inLocal;
layout(location = 2) in vec4 inColor;

layout(location = 0) out vec2 fragLocal;
layout(location = 1) out vec4 fragColor;

void main() {
    vec2 ndc = vec2(
        (inPosition.x / pc.viewportSize.x) * 2.0 - 1.0,
        (inPosition.y / pc.viewportSize.y) * 2.0 - 1.0
    );
    gl_Position = vec4(ndc, 0.0, 1.0);
    fragLocal = inLocal;
    fragColor = inColor;
}
