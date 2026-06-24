#version 450

layout(push_constant) uniform PushConstants {
    vec2 viewportSize;
    vec2 textureSize;
    vec4 rect;
    float opacity;
    float cornerRadius;
    vec2 textureOrigin;
    float bezelWidth;
    float glassThickness;
    float adaptiveAppearance;
    float adaptiveContrast;
} pc;

layout(location = 0) out vec2 fragScreen;

vec2 unitPositions[6] = vec2[](
    vec2(0.0, 0.0),
    vec2(1.0, 0.0),
    vec2(0.0, 1.0),
    vec2(1.0, 0.0),
    vec2(0.0, 1.0),
    vec2(1.0, 1.0)
);

void main() {
    vec2 unitPosition = unitPositions[gl_VertexIndex];
    vec2 screen = mix(pc.rect.xy, pc.rect.zw, unitPosition);
    vec2 ndc = vec2(
        (screen.x / pc.viewportSize.x) * 2.0 - 1.0,
        (screen.y / pc.viewportSize.y) * 2.0 - 1.0
    );
    gl_Position = vec4(ndc, 0.0, 1.0);
    fragScreen = screen;
}
