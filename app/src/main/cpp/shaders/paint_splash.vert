#version 450

struct Droplet {
    vec2 position;
    vec2 velocity;
    vec4 color;
    vec2 srcUv;
    float baseSize;
    float flightAge;
    float rotation;
    float lifetime;
    float dragFactor;
    uint phase;
};

layout(set = 0, binding = 0, std430) readonly buffer DropletBuffer {
    Droplet droplets[];
};

layout(push_constant) uniform PushConstants {
    vec2 viewportSize;
    vec2 itemOrigin;
    vec2 itemSize;
    float blobScale;
} pc;

layout(location = 0) out vec2 fragLocal;
layout(location = 1) out vec4 fragColor;

const uint SPLASH_PHASE_MASK = 3u;
const uint SPLASH_PHASE_FADING = 1u;

vec2 quadVertices[6] = vec2[](
    vec2(-1.0, -1.0),
    vec2(1.0, -1.0),
    vec2(1.0, 1.0),
    vec2(-1.0, -1.0),
    vec2(1.0, 1.0),
    vec2(-1.0, 1.0)
);

void main() {
    Droplet d = droplets[gl_InstanceIndex];
    if (d.lifetime < -0.5 || d.color.a <= 0.001) {
        gl_Position = vec4(-10.0, -10.0, 0.0, 1.0);
        fragLocal = vec2(0.0);
        fragColor = vec4(0.0);
        return;
    }

    vec2 q = quadVertices[gl_VertexIndex];
    float halfExtent = d.baseSize * pc.blobScale * 0.5;
    float cosR = cos(d.rotation);
    float sinR = sin(d.rotation);
    vec2 local = q * halfExtent;
    vec2 rotated = vec2(
        local.x * cosR - local.y * sinR,
        local.x * sinR + local.y * cosR
    );
    vec2 screen = pc.itemOrigin + d.position + rotated;
    vec2 ndc = vec2(
        (screen.x / pc.viewportSize.x) * 2.0 - 1.0,
        (screen.y / pc.viewportSize.y) * 2.0 - 1.0
    );

    float alpha = d.color.a;
    if ((d.phase & SPLASH_PHASE_MASK) == SPLASH_PHASE_FADING) {
        alpha *= max(d.lifetime / 0.15, 0.0);
    }

    gl_Position = vec4(ndc, 0.0, 1.0);
    fragLocal = q;
    fragColor = vec4(d.color.rgb, alpha);
}
