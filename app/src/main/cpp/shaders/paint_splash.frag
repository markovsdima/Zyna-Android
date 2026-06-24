#version 450

layout(location = 0) in vec2 fragLocal;
layout(location = 1) in vec4 fragColor;

layout(location = 0) out vec4 outColor;

void main() {
    float dist2 = dot(fragLocal, fragLocal);
    float energy = exp(-dist2 * 5.0);
    if (energy < 0.001) {
        discard;
    }
    float mass = energy * fragColor.a;
    outColor = vec4(fragColor.rgb * mass, mass);
}
