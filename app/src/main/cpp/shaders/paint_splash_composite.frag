#version 450

layout(set = 0, binding = 0) uniform sampler2D blobTex;

layout(location = 0) in vec2 fragUv;
layout(location = 0) out vec4 outColor;

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
    vec4 blob = texture(blobTex, fragUv);
    float energy = blob.a;
    if (energy < 0.05) {
        discard;
    }

    vec2 textureSizePx = vec2(textureSize(blobTex, 0));
    vec2 pixelCoord = fragUv * textureSizePx;
    float n = splashNoise(pixelCoord * 0.04) * 0.06
        + splashNoise(pixelCoord * 0.12) * 0.04;

    float threshold = 0.40;
    float edge = smoothstep(threshold - 0.06 + n, threshold + 0.02 + n, energy);
    if (edge < 0.01) {
        discard;
    }

    vec3 baseColor = blob.rgb / max(energy, 0.001);
    vec2 texelSize = 1.0 / textureSizePx;
    float eL = texture(blobTex, fragUv + vec2(-texelSize.x, 0.0)).a;
    float eR = texture(blobTex, fragUv + vec2(texelSize.x, 0.0)).a;
    float eU = texture(blobTex, fragUv + vec2(0.0, -texelSize.y)).a;
    float eD = texture(blobTex, fragUv + vec2(0.0, texelSize.y)).a;

    vec3 normal = normalize(vec3((eL - eR) * 2.0, (eU - eD) * 2.0, 0.15));
    vec3 lightDir = normalize(vec3(0.3, -0.5, 1.0));
    float diffuse = max(dot(normal, lightDir), 0.0) * 0.2 + 0.8;
    vec3 halfVec = normalize(lightDir + vec3(0.0, 0.0, 1.0));
    float spec = pow(max(dot(normal, halfVec), 0.0), 32.0);

    float depth = smoothstep(threshold, threshold + 0.3, energy);
    vec3 finalColor = baseColor * diffuse + vec3(spec * 0.3);
    finalColor *= 0.88 + 0.12 * depth;

    float alphaFromEnergy = smoothstep(threshold, threshold + 0.4, energy);
    float alphaNoise = splashNoise(pixelCoord * 0.06) * 0.15;
    float finalAlpha = edge * (0.65 + 0.35 * alphaFromEnergy + alphaNoise);

    outColor = vec4(clamp(finalColor, 0.0, 1.0), clamp(finalAlpha, 0.0, 1.0));
}
