#version 450

layout(set = 0, binding = 0) uniform sampler2D sphTex;

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
    vec4 blob = texture(sphTex, fragUv);
    float energy = blob.a;
    if (energy < 0.090) {
        discard;
    }

    vec2 pixelCoord = fragUv * vec2(textureSize(sphTex, 0));
    float n = splashNoise(pixelCoord * 0.045) * 0.018
        + splashNoise(pixelCoord * 0.125) * 0.014;
    float threshold = 0.20;
    float edge = smoothstep(threshold - 0.020 + n, threshold + 0.026 + n, energy);
    if (edge < 0.030) {
        discard;
    }

    vec3 baseColor = clamp(blob.rgb / max(energy, 0.001), 0.0, 1.0);
    vec2 texelSize = 1.0 / max(vec2(textureSize(sphTex, 0)), vec2(1.0));
    float eL = texture(sphTex, fragUv + vec2(-texelSize.x, 0.0)).a;
    float eR = texture(sphTex, fragUv + vec2( texelSize.x, 0.0)).a;
    float eU = texture(sphTex, fragUv + vec2(0.0, -texelSize.y)).a;
    float eD = texture(sphTex, fragUv + vec2(0.0,  texelSize.y)).a;

    vec3 normal = normalize(vec3((eL - eR) * 2.4, (eU - eD) * 2.7, 0.18));
    vec3 lightDir = normalize(vec3(-0.28, -0.58, 1.0));
    float diffuse = max(dot(normal, lightDir), 0.0) * 0.18 + 0.82;
    vec3 halfVec = normalize(lightDir + vec3(0.0, 0.0, 1.0));
    float spec = pow(max(dot(normal, halfVec), 0.0), 42.0)
        * smoothstep(threshold + 0.05, threshold + 0.34, energy)
        * edge;
    float depth = smoothstep(threshold, threshold + 0.42, energy);
    float alpha = edge * (0.82 + depth * 0.16);
    vec3 color = baseColor * diffuse * (0.88 + depth * 0.18)
        + mix(vec3(0.94, 0.98, 1.0), baseColor, 0.24) * spec * 0.18;

    outColor = vec4(clamp(color, 0.0, 1.0), alpha);
}
