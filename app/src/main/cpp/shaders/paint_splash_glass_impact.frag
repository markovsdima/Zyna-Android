#version 450

layout(location = 0) in vec2 fragUv;
layout(location = 1) in vec4 fragColor;
layout(location = 2) in vec2 fragVelocity;
layout(location = 3) in float fragAge;
layout(location = 4) in float fragLifetime;
layout(location = 5) in float fragSeed;
layout(location = 6) in float fragStretch;
layout(location = 7) in float fragImpact;

layout(location = 0) out vec4 outSurface;
layout(location = 1) out vec4 outVelocity;

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

float capsuleDistance(vec2 p, vec2 a, vec2 b, float r) {
    vec2 pa = p - a;
    vec2 ba = b - a;
    float h = saturate(dot(pa, ba) / max(dot(ba, ba), 0.0001));
    return length(pa - ba * h) - r;
}

void main() {
    vec2 p = fragUv;
    vec2 center = vec2(0.5);
    float wobble = (splashNoise(vec2(fragSeed * 21.0, 2.7)) - 0.5) * 0.030;
    center.x += wobble;

    float edgeFade = smoothstep(0.018, 0.070, p.y)
        * (1.0 - smoothstep(0.925, 0.995, p.y))
        * smoothstep(0.018, 0.070, p.x)
        * (1.0 - smoothstep(0.925, 0.995, p.x));

    vec2 fallDirection = normalize(vec2(fragVelocity.x * 0.18, max(abs(fragVelocity.y), 24.0)));
    float stretch01 = saturate((fragStretch - 1.0) / 1.2);
    vec2 coreP = (p - center) * vec2(1.0 + stretch01 * 0.10, 1.05 - stretch01 * 0.10);
    float core = exp(-dot(coreP, coreP) * mix(12.5, 8.7, stretch01));

    vec2 smearStart = center + fallDirection * 0.035;
    vec2 smearEnd = center + fallDirection * mix(0.115, 0.190, stretch01);
    float smearDistance = capsuleDistance(p, smearStart, smearEnd, mix(0.060, 0.088, fragImpact));
    float smear = (1.0 - smoothstep(0.0, 0.095, smearDistance)) * mix(0.22, 0.40, stretch01);

    float crownNoise = splashNoise((p + fragSeed) * 34.0) * 0.035;
    float field = max(core, smear) + crownNoise * smoothstep(0.18, 0.58, core);
    field = smoothstep(0.120, 0.82, field) * edgeFade;
    if (field <= 0.012) {
        discard;
    }

    float impactScale = mix(0.48, 0.88, saturate(fragImpact));
    float mass = field * fragColor.a * impactScale;
    vec3 paintColor = clamp(fragColor.rgb, 0.0, 1.0);
    vec2 initialVelocity = vec2(
        fragVelocity.x * 0.52,
        max(fragVelocity.y, 8.0) * 0.42 + 24.0 * fragImpact
    );

    outSurface = vec4(paintColor * mass, mass);
    outVelocity = vec4(initialVelocity * mass, 0.0, mass);
}
