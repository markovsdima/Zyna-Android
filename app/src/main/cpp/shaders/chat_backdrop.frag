#version 450

layout(set = 0, binding = 0) uniform sampler2D clearTex;
layout(set = 0, binding = 1) uniform sampler2D blurTex;
layout(set = 0, binding = 2) uniform sampler2D splashSurfaceTex;

layout(push_constant) uniform PushConstants {
    vec2 viewportSize;
    vec2 textureSize;
    vec4 rect;
    float opacity;
    float cornerRadius;
    vec2 textureOrigin;
    float bezelWidth;
    float glassThickness;
    float adaptiveAppearance;
    float adaptiveContrast;
    float splashSurfaceIntensity;
    float splashSurfaceAge;
} pc;

layout(location = 0) in vec2 fragScreen;
layout(location = 0) out vec4 outColor;

const float CHROMA_SPREAD = 0.02;
const float TINT_GRAY = 0.45;
const float TINT_STRENGTH = 0.04;
const float BORDER_WIDTH = 0.08;
const float BORDER_BRIGHTNESS = 0.3;
const float BORDER_COLOR_MIX = 0.3;

float saturate(float value) {
    return clamp(value, 0.0, 1.0);
}

vec2 saturate(vec2 value) {
    return clamp(value, vec2(0.0), vec2(1.0));
}

vec3 saturate(vec3 value) {
    return clamp(value, vec3(0.0), vec3(1.0));
}

float sdRoundedRect(vec2 point, vec2 halfExtent, float radius) {
    float safeRadius = min(radius, min(halfExtent.x, halfExtent.y));
    vec2 q = abs(point) - halfExtent + vec2(safeRadius);
    return length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - safeRadius;
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

float splashPaintedSurfaceMask(vec4 state) {
    float mass = max(state.a, 0.0);
    if (mass <= 0.001) {
        return 0.0;
    }
    float paintMass = max(max(state.r, state.g), state.b);
    float paintRatio = paintMass / max(mass, 0.001);
    return smoothstep(0.006, 0.026, paintMass)
        * smoothstep(0.010, 0.055, paintRatio);
}

float splashHeightFromEnergy(float energy) {
    return saturate(1.0 - exp(-max(energy, 0.0) * 1.35));
}

struct SplashSurface {
    float energy;
    float trail;
    float edge;
    vec2 normal;
    vec3 color;
};

SplashSurface sampleSplashSurface(vec2 screenPoint) {
    SplashSurface surface;
    surface.energy = 0.0;
    surface.trail = 0.0;
    surface.edge = 0.0;
    surface.normal = vec2(0.0);
    surface.color = vec3(0.0);

    float intensity = saturate(pc.splashSurfaceIntensity);
    if (intensity <= 0.001) {
        return surface;
    }

    vec2 uv = screenPoint / max(pc.viewportSize, vec2(1.0));
    if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) {
        return surface;
    }

    vec2 splashTexel = 1.0 / max(vec2(textureSize(splashSurfaceTex, 0)), vec2(1.0));
    vec2 texel = splashTexel;
    vec2 smoothStep = texel * 0.88;
    vec4 centerSample = texture(splashSurfaceTex, uv);
    vec4 leftSample = texture(splashSurfaceTex, clamp(uv - vec2(smoothStep.x, 0.0), vec2(0.0), vec2(1.0)));
    vec4 rightSample = texture(splashSurfaceTex, clamp(uv + vec2(smoothStep.x, 0.0), vec2(0.0), vec2(1.0)));
    vec4 upSample = texture(splashSurfaceTex, clamp(uv - vec2(0.0, smoothStep.y), vec2(0.0), vec2(1.0)));
    vec4 downSample = texture(splashSurfaceTex, clamp(uv + vec2(0.0, smoothStep.y), vec2(0.0), vec2(1.0)));
    vec4 raw = centerSample * 0.58;
    raw += leftSample * 0.105;
    raw += rightSample * 0.105;
    raw += upSample * 0.105;
    raw += downSample * 0.105;
    float paintedSource = splashPaintedSurfaceMask(raw);
    if (paintedSource <= 0.001) {
        return surface;
    }

    float height = splashHeightFromEnergy(raw.a);
    float visibleBody = smoothstep(0.030, 0.24, height);
    surface.energy = visibleBody * intensity * paintedSource;

    float hL = splashHeightFromEnergy(mix(raw.a, leftSample.a, 0.72));
    float hR = splashHeightFromEnergy(mix(raw.a, rightSample.a, 0.72));
    float hU = splashHeightFromEnergy(mix(raw.a, upSample.a, 0.72));
    float hD = splashHeightFromEnergy(mix(raw.a, downSample.a, 0.72));
    vec2 grad = vec2(hR - hL, hD - hU);
    float gradLen = length(grad);
    surface.normal = clamp(grad * 8.2, vec2(-0.86), vec2(0.86));

    float rimBand = smoothstep(0.052, 0.24, height)
        * (1.0 - smoothstep(0.68, 0.98, height));
    surface.edge = smoothstep(0.007, 0.058, gradLen) * rimBand * intensity * paintedSource;
    surface.trail = smoothstep(0.040, 0.16, height)
        * (1.0 - smoothstep(0.22, 0.58, height))
        * intensity
        * paintedSource;
    surface.edge = max(surface.edge, surface.trail * 0.10 * rimBand);
    surface.color = raw.rgb / max(raw.a, 0.001);
    return surface;
}

float squircleSlope(float x, float exponent) {
    float xc = clamp(x, 0.001, 1.0);
    float n = max(exponent, 2.0);
    float u = 1.0 - xc;
    float uN = pow(u, n);
    float inner = max(1.0 - uN, 0.000001);
    return pow(u, n - 1.0) * pow(inner, 1.0 / n - 1.0);
}

vec2 screenToUv(vec2 screen) {
    return (screen - pc.textureOrigin) / max(pc.textureSize, vec2(1.0));
}

vec2 clampUv(vec2 uv) {
    vec2 texel = 1.0 / max(pc.textureSize, vec2(1.0));
    return clamp(uv, texel * 0.5, vec2(1.0) - texel * 0.5);
}

vec3 sampleBackdropRgb(vec2 uv) {
    return texture(clearTex, clampUv(uv)).rgb;
}

vec3 sampleBlurRgb(vec2 uv) {
    return texture(blurTex, clampUv(uv)).rgb;
}

void main() {
    vec2 uv = screenToUv(fragScreen);
    vec2 rectOrigin = screenToUv(pc.rect.xy);
    vec2 rectSize = max((pc.rect.zw - pc.rect.xy) / max(pc.textureSize, vec2(1.0)), vec2(0.0001));
    vec2 rectCenter = rectOrigin + rectSize * 0.5;

    float aspect = pc.textureSize.x / max(pc.textureSize.y, 1.0);
    vec2 p = vec2(uv.x * aspect, uv.y);
    vec2 shapeCenter = vec2(rectCenter.x * aspect, rectCenter.y);
    vec2 shapeHalf = vec2(rectSize.x * aspect * 0.5, rectSize.y * 0.5);
    float shapeCornerR = min(pc.cornerRadius / max(pc.textureSize.y, 1.0), shapeHalf.y);
    vec2 localPoint = p - shapeCenter;
    float sdf = sdRoundedRect(localPoint, shapeHalf, shapeCornerR);

    float scaleY = rectSize.y;
    float glassMask = 1.0 - smoothstep(-0.005 * scaleY, 0.003 * scaleY, sdf);
    if (glassMask < 0.001) {
        discard;
    }

    float eps = 0.001;
    float gx = sdRoundedRect(localPoint + vec2(eps, 0.0), shapeHalf, shapeCornerR) - sdf;
    float gy = sdRoundedRect(localPoint + vec2(0.0, eps), shapeHalf, shapeCornerR) - sdf;
    vec2 grad = vec2(gx, gy);
    float gradLen = length(grad);
    vec2 edgeNormal = gradLen > 0.00001 ? grad / gradLen : vec2(0.0);

    float distFromEdge = -sdf;
    float bw = max(pc.bezelWidth / max(pc.textureSize.y, 1.0), 0.0001);
    float normDist = saturate(distFromEdge / bw);
    float slope = squircleSlope(normDist, 6.0);

    float eta = 1.0 / 1.5;
    float slope2 = slope * slope;
    float invLength = inversesqrt(1.0 + slope2);
    float cosI = invLength;
    float sinI = slope * invLength;
    float cosT = sqrt(max(1.0 - eta * eta * sinI * sinI, 0.0));
    float lateral = sinI * (cosT - eta * cosI);

    vec2 refractDir = vec2(edgeNormal.x / aspect, edgeNormal.y);
    vec2 offset = refractDir *
        lateral *
        (pc.glassThickness / max(pc.textureSize.y, 1.0)) *
        1.1;

    SplashSurface splashSurface = sampleSplashSurface(fragScreen);
    float wetEnergy = saturate(max(splashSurface.energy, splashSurface.trail * 0.72));
    float dropThickness = pow(wetEnergy, 0.74);
    float wetRipple = (dropThickness * 0.82 + splashSurface.edge * 0.20)
        * smoothstep(0.0, bw * 0.18, distFromEdge);
    offset += splashSurface.normal
        * wetRipple
        * (pc.glassThickness / max(pc.textureSize.y, 1.0))
        * 0.58;

    float chromaAmount = slope * CHROMA_SPREAD;
    vec2 uvR = clampUv(uv - offset * (1.0 - chromaAmount));
    vec2 uvG = clampUv(uv - offset);
    vec2 uvB = clampUv(uv - offset * (1.0 + chromaAmount));

    vec3 blurR = sampleBlurRgb(uvR);
    vec3 blurG = sampleBlurRgb(uvG);
    vec3 blurB = sampleBlurRgb(uvB);
    vec3 color = vec3(blurR.r, blurG.g, blurB.b);

    if (wetEnergy > 0.001) {
        vec3 clearWet = sampleBackdropRgb(uvG);
        color = mix(color, clearWet, saturate(wetEnergy * 0.26 + splashSurface.trail * 0.10));

        vec3 rawPaint = clamp(splashSurface.color, 0.0, 1.0);
        float paintLuma = dot(rawPaint, vec3(0.299, 0.587, 0.114));
        vec3 paintColor = clamp(mix(vec3(paintLuma), rawPaint, 1.42), 0.0, 1.0);
        float body = saturate(splashSurface.energy * 0.95 + splashSurface.trail * 0.42);
        float absorption = saturate(body * 0.54 + splashSurface.edge * 0.035);
        vec3 transmission = mix(vec3(1.0), paintColor, 0.58);
        vec3 coloredGlass = color * transmission + paintColor * (0.12 + body * 0.18);
        color = mix(color, coloredGlass, absorption);

        float surfaceNoise = splashNoise(vec2(
            uv.x * 130.0,
            uv.y * 42.0 + pc.splashSurfaceAge * 0.8
        ));
        float thinStream = smoothstep(0.70, 1.0, surfaceNoise) * splashSurface.trail;
        float contactShadow = splashSurface.edge * 0.10 + thinStream * 0.045;
        color *= 1.0 - contactShadow;

        vec3 dropNormal = normalize(vec3(
            -splashSurface.normal.x * 0.62,
            -splashSurface.normal.y * 0.88,
            1.0
        ));
        vec3 dropLight = normalize(vec3(-0.32, -0.62, 1.0));
        float primarySpec = pow(saturate(dot(dropNormal, dropLight)), 28.0)
            * splashSurface.edge * 0.82;
        float wetSheen = pow(saturate(dot(dropNormal, normalize(vec3(0.20, -0.46, 1.0)))), 46.0)
            * wetEnergy * 0.08;
        color += mix(vec3(0.90, 0.95, 1.0), paintColor, 0.10)
            * (primarySpec + wetSheen) * 0.42;
        color += paintColor * (thinStream * 0.10 + splashSurface.energy * 0.13 + splashSurface.edge * 0.08);
    }

    float luma = dot(color, vec3(0.299, 0.587, 0.114));
    float targetLuma = mix(luma, TINT_GRAY, TINT_STRENGTH);
    vec3 chroma = (color - vec3(luma)) * 0.92;
    color = saturate(vec3(targetLuma) + chroma);

    float appearance = clamp(pc.adaptiveAppearance, 0.0, 1.0);
    float contrast = clamp(pc.adaptiveContrast, 0.0, 1.0);
    float adaptiveTarget = mix(0.18, 0.82, appearance);
    float adaptiveStrength = mix(0.16, 0.38, contrast);

    luma = dot(color, vec3(0.299, 0.587, 0.114));
    chroma = color - vec3(luma);
    float adaptiveLuma = mix(luma, adaptiveTarget, adaptiveStrength);
    float chromaKeep = mix(0.90, 0.68, contrast);
    color = saturate(vec3(adaptiveLuma) + chroma * chromaKeep);

    float preLuma = dot(color, vec3(0.299, 0.587, 0.114));
    color += vec3(0.034) * appearance * (1.0 - smoothstep(0.0, 0.12, preLuma));
    color *= mix(0.92, 1.10, appearance);
    float boostLuma = dot(color, vec3(0.299, 0.587, 0.114));
    color = mix(vec3(boostLuma), color, 1.08);

    float fresnelZone = saturate(distFromEdge / (bw * 0.15));
    float fresnel = pow(1.0 - fresnelZone, 4.0);
    float lightDir = saturate((-edgeNormal.x - edgeNormal.y) * 0.5 + 0.5);
    color += vec3(1.0, 1.0, 1.02) * fresnel * mix(0.02, 0.08, lightDir);

    float shadowZone = smoothstep(0.0, bw * 0.12, distFromEdge);
    float shadowSide = saturate((edgeNormal.x + edgeNormal.y) * 0.5 + 0.5);
    color *= 1.0 - (1.0 - shadowZone) * shadowSide * 0.08;

    float borderInner = bw * 0.02;
    float borderOuter = BORDER_WIDTH * bw;
    float borderMask = smoothstep(borderInner, borderInner + borderOuter * 0.3, distFromEdge)
        * (1.0 - smoothstep(borderInner + borderOuter * 0.3, borderInner + borderOuter, distFromEdge));
    float borderBrightness = BORDER_BRIGHTNESS * mix(0.05, 0.5, lightDir);
    float refLuma = dot(color, vec3(0.299, 0.587, 0.114));
    vec3 borderColor = mix(
        vec3(1.0),
        saturate(mix(vec3(refLuma), color, 1.5)),
        BORDER_COLOR_MIX
    );
    color += borderColor * borderMask * borderBrightness;

    outColor = vec4(saturate(color), glassMask);
}
