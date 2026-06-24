#version 450

layout(location = 0) out vec2 fragUv;

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
    fragUv = unitPosition;
    gl_Position = vec4(unitPosition * 2.0 - 1.0, 0.0, 1.0);
}
