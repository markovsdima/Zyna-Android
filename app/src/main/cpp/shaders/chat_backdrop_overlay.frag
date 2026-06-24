#version 450

layout(set = 0, binding = 0) uniform sampler2D sourceTex;
layout(set = 0, binding = 1) uniform sampler2D splashBlobTex;

layout(push_constant) uniform PushConstants {
    vec2 viewportSize;
    vec2 textureSize;
    vec2 textureOrigin;
    float overlayAlpha;
    float _padding0;
} pc;

layout(location = 0) in vec2 fragUv;
layout(location = 0) out vec4 outColor;

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
    float a = splashHash(i);
    float b = splashHash(i + vec2(1.0, 0.0));
    float c = splashHash(i + vec2(0.0, 1.0));
    float d = splashHash(i + vec2(1.0, 1.0));
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

vec4 samplePaintSplashBlob(vec2 uv) {
    if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) {
        return vec4(0.0);
    }

    vec4 blob = texture(splashBlobTex, uv);
    float energy = blob.a;
    if (energy < 0.05) {
        return vec4(0.0);
    }

    vec2 pixelCoord = uv * pc.viewportSize;
    float n = splashNoise(pixelCoord * 0.04) * 0.06
        + splashNoise(pixelCoord * 0.12) * 0.04;
    float threshold = 0.4;
    float edge = smoothstep(threshold - 0.06 + n, threshold + 0.02 + n, energy);
    if (edge < 0.01) {
        return vec4(0.0);
    }

    vec3 baseColor = blob.rgb / max(energy, 0.001);
    vec2 texelSize = 1.0 / max(pc.viewportSize, vec2(1.0));
    float eL = texture(splashBlobTex, uv + vec2(-texelSize.x, 0.0)).a;
    float eR = texture(splashBlobTex, uv + vec2(texelSize.x, 0.0)).a;
    float eU = texture(splashBlobTex, uv + vec2(0.0, -texelSize.y)).a;
    float eD = texture(splashBlobTex, uv + vec2(0.0, texelSize.y)).a;

    vec3 normal = normalize(vec3((eL - eR) * 2.0, (eU - eD) * 2.0, 0.15));
    vec3 lightDir = normalize(vec3(0.3, -0.5, 1.0));
    float diffuse = max(dot(normal, lightDir), 0.0) * 0.2 + 0.8;
    vec3 halfVec = normalize(lightDir + vec3(0.0, 0.0, 1.0));
    float spec = pow(max(dot(normal, halfVec), 0.0), 32.0);

    vec3 color = baseColor * diffuse + spec * 0.3;
    return vec4(color, edge * 0.95);
}

void main() {
    vec2 sourceUv = clamp(fragUv, vec2(0.0), vec2(1.0));
    vec4 base = texture(sourceTex, sourceUv);

    vec2 screenPoint = pc.textureOrigin + sourceUv * pc.textureSize;
    vec2 overlayUv = screenPoint / max(pc.viewportSize, vec2(1.0));
    vec4 splash = samplePaintSplashBlob(overlayUv);

    float alpha = saturate(splash.a * pc.overlayAlpha);
    vec3 color = mix(base.rgb, splash.rgb, alpha);
    outColor = vec4(color, max(base.a, alpha));
}
