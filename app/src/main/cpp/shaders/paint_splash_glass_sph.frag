#version 450

layout(push_constant) uniform PushConstants {
    vec2 viewportSize;
    float visibleFade;
    float _padding0;
} pc;

layout(location = 0) in vec2 fragUv;
layout(location = 1) in vec2 fragOverlayPoint;
layout(location = 2) in vec2 fragCenter;
layout(location = 3) in vec2 fragAnchor;
layout(location = 4) in vec4 fragColor;
layout(location = 5) in vec2 fragVelocity;
layout(location = 6) in float fragRadius;
layout(location = 7) in float fragMass;
layout(location = 8) in float fragState;
layout(location = 9) in float fragAge;
layout(location = 10) in float fragAnchorStrength;
layout(location = 11) in vec2 fragSurfaceNormal;
layout(location = 12) in float fragSurfaceCurvature;
layout(location = 13) in float fragProfile;

layout(location = 0) out vec4 outColor;

const float DRIP_FLAT_BODY_SCALE = 2.65;
const float DRIP_CURVED_BODY_SCALE = 2.16;
const float DRIP_FLAT_NECK_SCALE = 2.22;
const float DRIP_CURVED_NECK_SCALE = 1.52;
const float DRIP_FLAT_RIM_SCALE = 1.42;
const float DRIP_CURVED_RIM_SCALE = 0.82;

float saturate(float value) {
    return clamp(value, 0.0, 1.0);
}

float splashHash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float splashNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = splashHash(i);
    float b = splashHash(i + vec2(1.0, 0.0));
    float c = splashHash(i + vec2(0.0, 1.0));
    float d = splashHash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

void main() {
    float alphaBase = pc.visibleFade * smoothstep(0.012, 0.12, fragMass);
    if (alphaBase <= 0.010) {
        discard;
    }

    vec2 p = fragOverlayPoint;
    vec2 center = fragCenter;
    vec2 anchor = fragAnchor;
    float contact = fragState < 1.5 ? saturate(fragAnchorStrength / 6.0) : 0.0;
    bool attached = fragState < 1.5;
    float fallStretch = attached ? 0.0 : saturate(max(fragVelocity.y, 0.0) / 520.0);
    float curvedSource = smoothstep(0.003, 0.014, max(fragSurfaceCurvature, 0.0));
    float baseRadius = max(fragRadius, 0.001);
    float radius = baseRadius * mix(DRIP_FLAT_BODY_SCALE, DRIP_CURVED_BODY_SCALE, curvedSource);
    float neckBaseRadius = baseRadius * mix(DRIP_FLAT_NECK_SCALE, DRIP_CURVED_NECK_SCALE, curvedSource);
    float rimBaseRadius = baseRadius * mix(DRIP_FLAT_RIM_SCALE, DRIP_CURVED_RIM_SCALE, curvedSource);
    vec2 surfaceNormal = length(fragSurfaceNormal) > 0.001
        ? normalize(fragSurfaceNormal)
        : vec2(0.0, 1.0);
    vec2 surfaceTangent = vec2(-surfaceNormal.y, surfaceNormal.x);
    float profile = fragProfile;
    float sticky = 1.0 - smoothstep(0.18, 0.46, profile);
    float thin = smoothstep(0.18, 0.48, profile) * (1.0 - smoothstep(0.52, 0.78, profile));
    float heavy = smoothstep(0.62, 0.96, profile);
    vec2 tether = center - anchor;
    float tetherLen = length(tether);
    vec2 axis = tetherLen > 0.40 ? tether / tetherLen : vec2(0.0, 1.0);
    if (!attached && length(fragVelocity) > 4.0) {
        axis = normalize(vec2(fragVelocity.x * 0.20, max(abs(fragVelocity.y), 24.0)));
    }
    vec2 side = vec2(-axis.y, axis.x);

    vec2 relCenter = p - center;
    float bodyX = dot(relCenter, side) + dot(relCenter, axis) * clamp(fragVelocity.x / 260.0, -0.16, 0.16);
    float bodyY = dot(relCenter, axis);
    float pearY = bodyY / radius;
    float shoulder = smoothstep(-1.05, -0.18, pearY);
    float belly = exp(-pow((pearY - 0.18) / 0.72, 2.0));
    float bodyRx = radius * (0.55 + shoulder * 0.32 + belly * 0.18)
        * mix(1.0, 0.78, fallStretch)
        * mix(0.62, 1.18, heavy)
        * mix(1.0, 0.68, thin);
    float bodyRy = radius * (1.02 + fallStretch * 0.72)
        * mix(0.82, 1.26, heavy)
        * mix(1.16, 0.84, sticky);
    vec2 bodyP = vec2(bodyX / max(bodyRx, 0.001), bodyY / max(bodyRy, 0.001));
    float bodyDensity = exp(-dot(bodyP, bodyP) * 1.62);

    float neckDensity = 0.0;
    float meniscusDensity = 0.0;
    if (attached) {
        vec2 pa = p - anchor;
        float h = saturate(dot(pa, tether) / max(dot(tether, tether), 0.0001));
        vec2 segmentPoint = anchor + tether * h;
        float segmentDistance = length(p - segmentPoint);
        float neckRadius = neckBaseRadius * mix(
            0.68,
            0.22,
            smoothstep(0.22, 0.86, tetherLen / max(neckBaseRadius * 4.0, 0.001))
        );
        neckRadius *= mix(1.10, 0.74, contact)
            * mix(0.55, 1.18, sticky)
            * mix(0.62, 1.0, thin);
        float neckCore = exp(-pow(segmentDistance / max(neckRadius, 0.001), 2.0) * 1.16);
        float neckTaper = mix(0.98, mix(0.20, 0.48, sticky), smoothstep(0.18, 0.92, h));
        neckDensity = neckCore * neckTaper * smoothstep(0.05, 0.36, tetherLen / max(radius, 0.001));

        vec2 relAnchor = p - anchor;
        float rimX = dot(relAnchor, surfaceTangent);
        float rimY = dot(relAnchor, surfaceNormal);
        float rimRadius = mix(rimBaseRadius, baseRadius * 1.05, curvedSource);
        float rimContact = contact * mix(1.0, 0.56, curvedSource);
        float surfaceCurve = -min(0.5 * max(fragSurfaceCurvature, 0.0) * rimX * rimX,
                                  max(rimRadius * 1.35, 1.0));
        float surfaceY = rimY - surfaceCurve;
        float filmWidth = rimRadius * mix(1.18, 2.42, rimContact)
            * mix(0.64, 1.32, thin + sticky * 0.35);
        float filmDepth = rimRadius * mix(0.16, 0.46, rimContact)
            * mix(0.72, 1.24, sticky + heavy * 0.30);
        float film = exp(-pow(rimX / max(filmWidth, 0.001), 2.0) * 0.92)
            * exp(-pow(max(surfaceY, 0.0) / max(filmDepth, 0.001), 2.0) * 1.18)
            * smoothstep(-0.9, 0.75, surfaceY);
        float rootBead = exp(-(
            pow(rimX / max(filmWidth * 0.42, 0.001), 2.0)
            + pow((max(surfaceY, 0.0) - filmDepth * 0.48) / max(filmDepth * 0.78, 0.001), 2.0)
        ) * 1.10);
        meniscusDensity = film * mix(0.30, 0.78, rimContact) * mix(0.78, 1.22, sticky + thin * 0.25)
            + rootBead * mix(0.10, 0.30, rimContact) * mix(0.80, 1.18, heavy + sticky * 0.35);
        meniscusDensity *= mix(0.96, 0.34, curvedSource);
    }

    float density = max(bodyDensity, max(neckDensity * 0.86, meniscusDensity));
    density += neckDensity * 0.28 + meniscusDensity * 0.22;
    float edgeNoise = splashNoise(vec2(fragAge * 7.0 + fragMass * 11.0,
                                       dot(p, vec2(0.041, 0.057))));
    density *= 0.96 + edgeNoise * 0.070;
    float compactDensity = smoothstep(0.18, 0.74, density);
    compactDensity *= compactDensity;
    if (compactDensity <= 0.003) {
        discard;
    }

    float energy = compactDensity * fragMass * mix(2.0, 2.65, contact) * alphaBase;
    vec3 color = clamp(fragColor.rgb, 0.0, 1.0);
    outColor = vec4(color * energy, energy);
}
