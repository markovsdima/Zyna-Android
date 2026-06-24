#version 450

struct GlassSPHParticle {
    vec4 positionVelocity;
    vec4 color;
    vec4 radiusMassDensityPressure;
    vec4 ageLifetimeSeedActive;
    vec4 anchorStrengthAnchorState;
    vec4 normalCurvatureProfilePad;
};

layout(set = 0, binding = 0, std430) readonly buffer ParticlesBuffer {
    GlassSPHParticle particles[];
};

layout(push_constant) uniform PushConstants {
    vec2 viewportSize;
    float visibleFade;
    float _padding0;
} pc;

layout(location = 0) out vec2 fragUv;
layout(location = 1) out vec2 fragOverlayPoint;
layout(location = 2) out vec2 fragCenter;
layout(location = 3) out vec2 fragAnchor;
layout(location = 4) out vec4 fragColor;
layout(location = 5) out vec2 fragVelocity;
layout(location = 6) out float fragRadius;
layout(location = 7) out float fragMass;
layout(location = 8) out float fragState;
layout(location = 9) out float fragAge;
layout(location = 10) out float fragAnchorStrength;
layout(location = 11) out vec2 fragSurfaceNormal;
layout(location = 12) out float fragSurfaceCurvature;
layout(location = 13) out float fragProfile;

const float DRIP_FLAT_BODY_SCALE = 2.65;
const float DRIP_CURVED_BODY_SCALE = 2.16;

vec2 unitVertices[6] = vec2[](
    vec2(0.0, 0.0),
    vec2(1.0, 0.0),
    vec2(0.0, 1.0),
    vec2(1.0, 0.0),
    vec2(0.0, 1.0),
    vec2(1.0, 1.0)
);

float saturate(float value) {
    return clamp(value, 0.0, 1.0);
}

void main() {
    GlassSPHParticle p = particles[gl_InstanceIndex];
    vec2 center = p.positionVelocity.xy;
    vec2 anchor = p.anchorStrengthAnchorState.yz;
    vec2 velocity = p.positionVelocity.zw;
    float radius = p.radiusMassDensityPressure.x;
    float mass = p.radiusMassDensityPressure.y;
    float state = p.anchorStrengthAnchorState.w;
    float isLive = p.ageLifetimeSeedActive.w;

    fragUv = vec2(0.0);
    fragOverlayPoint = vec2(0.0);
    fragCenter = center;
    fragAnchor = anchor;
    fragColor = p.color;
    fragVelocity = velocity;
    fragRadius = radius;
    fragMass = mass;
    fragState = state;
    fragAge = p.ageLifetimeSeedActive.x;
    fragAnchorStrength = p.anchorStrengthAnchorState.x;
    fragSurfaceNormal = p.normalCurvatureProfilePad.xy;
    fragSurfaceCurvature = p.normalCurvatureProfilePad.z;
    fragProfile = p.normalCurvatureProfilePad.w;

    if (isLive < 0.5 || mass <= 0.012 || pc.visibleFade <= 0.010) {
        gl_Position = vec4(-10.0, -10.0, 0.0, 1.0);
        return;
    }

    vec2 q = unitVertices[gl_VertexIndex];
    float curvedSource = smoothstep(0.003, 0.014, max(p.normalCurvatureProfilePad.z, 0.0));
    radius = max(radius, 1.0) * mix(DRIP_FLAT_BODY_SCALE, DRIP_CURVED_BODY_SCALE, curvedSource);
    bool attached = state < 1.5;
    float contact = attached ? saturate(p.anchorStrengthAnchorState.x / 6.0) : 0.0;
    float fallStretch = attached ? 0.0 : saturate(max(velocity.y, 0.0) / 520.0);
    vec2 minPoint = attached ? min(anchor, center) : center;
    vec2 maxPoint = attached ? max(anchor, center) : center;
    float tetherLen = length(center - anchor);
    float padX = radius * mix(3.4, 4.8, contact) + min(tetherLen * 0.20, radius * 2.4) + 5.0;
    float padTop = radius * mix(2.2, 1.6, contact) + 4.0;
    float padBottom = radius * (3.2 + fallStretch * 2.0) + 7.0;
    vec2 minCorner = vec2(minPoint.x - padX, minPoint.y - padTop);
    vec2 maxCorner = vec2(maxPoint.x + padX, maxPoint.y + padBottom);
    vec2 screenPos = mix(minCorner, maxCorner, q);
    vec2 ndc = vec2(
        (screenPos.x / pc.viewportSize.x) * 2.0 - 1.0,
        (screenPos.y / pc.viewportSize.y) * 2.0 - 1.0
    );

    gl_Position = vec4(ndc, 0.0, 1.0);
    fragUv = q;
    fragOverlayPoint = screenPos;
}
