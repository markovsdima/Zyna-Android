#version 450

struct GlassDroplet {
    vec2 position;
    vec2 velocity;
    vec4 color;
    float radius;
    float age;
    float lifetime;
    float seed;
    float stretch;
    float activeValue;
    float impact;
    float pad;
};

layout(set = 0, binding = 0, std430) readonly buffer GlassDropletBuffer {
    GlassDroplet glassDroplets[];
};

layout(push_constant) uniform PushConstants {
    vec2 viewportSize;
} pc;

layout(location = 0) out vec2 fragUv;
layout(location = 1) out vec4 fragColor;
layout(location = 2) out vec2 fragVelocity;
layout(location = 3) out float fragAge;
layout(location = 4) out float fragLifetime;
layout(location = 5) out float fragSeed;
layout(location = 6) out float fragStretch;
layout(location = 7) out float fragImpact;

vec2 quadVertices[6] = vec2[](
    vec2(0.0, 0.0),
    vec2(1.0, 0.0),
    vec2(0.0, 1.0),
    vec2(1.0, 0.0),
    vec2(0.0, 1.0),
    vec2(1.0, 1.0)
);

void main() {
    GlassDroplet gd = glassDroplets[gl_InstanceIndex];
    if (gd.activeValue < 0.5) {
        gl_Position = vec4(-10.0, -10.0, 0.0, 1.0);
        fragUv = vec2(0.0);
        fragColor = vec4(0.0);
        fragVelocity = vec2(0.0);
        fragAge = 0.0;
        fragLifetime = 1.0;
        fragSeed = 0.0;
        fragStretch = 1.0;
        fragImpact = 0.0;
        return;
    }

    vec2 q = quadVertices[gl_VertexIndex];
    float speedStretch = clamp(length(gd.velocity) / 180.0, 0.0, 1.0);
    float stretch = clamp(gd.stretch + speedStretch * 0.45, 1.0, 2.2);
    float width = gd.radius * (3.55 + gd.impact * 0.35);
    float height = gd.radius * (3.45 + stretch * 0.78);
    vec2 local = (q - vec2(0.5)) * vec2(width, height);

    float lean = clamp(gd.velocity.x / 160.0, -0.18, 0.18);
    local.x += local.y * lean;
    local.y += height * 0.035;

    vec2 screenPos = gd.position + local;
    vec2 ndc = vec2(
        (screenPos.x / pc.viewportSize.x) * 2.0 - 1.0,
        (screenPos.y / pc.viewportSize.y) * 2.0 - 1.0
    );

    gl_Position = vec4(ndc, 0.0, 1.0);
    fragUv = q;
    fragColor = gd.color;
    fragVelocity = gd.velocity;
    fragAge = gd.age;
    fragLifetime = gd.lifetime;
    fragSeed = gd.seed;
    fragStretch = stretch;
    fragImpact = gd.impact;
}
