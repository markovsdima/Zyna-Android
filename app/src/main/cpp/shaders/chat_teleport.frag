#version 450

layout(set = 0, binding = 0) uniform sampler2D oldSceneTex;
layout(set = 0, binding = 1) uniform sampler2D newSceneTex;

layout(push_constant) uniform PushConstants {
    vec2 outputSize;
    vec2 outputOrigin;
    vec4 viewportRect;
    vec2 sceneTextureSize;
    float progress;
    float directionSign;
} pc;

layout(location = 0) in vec2 fragUv;
layout(location = 0) out vec4 outColor;

bool isInside(vec2 uv) {
    return uv.x >= 0.0 && uv.y >= 0.0 && uv.x <= 1.0 && uv.y <= 1.0;
}

void main() {
    vec2 screen = pc.outputOrigin + fragUv * pc.outputSize;
    float viewportHeight = max(pc.viewportRect.w - pc.viewportRect.y, 1.0);
    float oldTop = pc.viewportRect.y + pc.directionSign * viewportHeight * pc.progress;
    float newTop = pc.viewportRect.y - pc.directionSign * viewportHeight * (1.0 - pc.progress);
    vec2 safeTextureSize = max(pc.sceneTextureSize, vec2(1.0));
    vec2 oldUv = (screen - vec2(pc.viewportRect.x, oldTop)) / safeTextureSize;
    vec2 newUv = (screen - vec2(pc.viewportRect.x, newTop)) / safeTextureSize;

    if (isInside(newUv)) {
        outColor = texture(newSceneTex, clamp(newUv, vec2(0.0), vec2(1.0)));
    } else if (isInside(oldUv)) {
        outColor = texture(oldSceneTex, clamp(oldUv, vec2(0.0), vec2(1.0)));
    } else {
        outColor = vec4(0.0);
    }
}
