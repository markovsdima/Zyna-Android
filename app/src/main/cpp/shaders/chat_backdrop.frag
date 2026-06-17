#version 450

layout(set = 0, binding = 0) uniform sampler2D backdropTex;

layout(push_constant) uniform PushConstants {
    vec2 viewportSize;
    vec2 textureSize;
    vec4 rect;
    float opacity;
    float cornerRadius;
    vec2 textureOrigin;
} pc;

layout(location = 0) in vec2 fragScreen;
layout(location = 0) out vec4 outColor;

float roundedRectAlpha(vec2 point, vec4 rect, float radius) {
    vec2 halfSize = max((rect.zw - rect.xy) * 0.5, vec2(1.0));
    vec2 center = (rect.xy + rect.zw) * 0.5;
    vec2 local = abs(point - center) - halfSize + vec2(radius);
    float dist = length(max(local, vec2(0.0))) + min(max(local.x, local.y), 0.0) - radius;
    return 1.0 - smoothstep(-0.5, 1.5, dist);
}

void main() {
    float shapeAlpha = roundedRectAlpha(fragScreen, pc.rect, pc.cornerRadius);
    if (shapeAlpha <= 0.01) {
        discard;
    }

    vec2 rectSize = max(pc.rect.zw - pc.rect.xy, vec2(1.0));
    vec2 rectCenter = (pc.rect.xy + pc.rect.zw) * 0.5;
    vec2 local = clamp((fragScreen - pc.rect.xy) / rectSize, vec2(0.0), vec2(1.0));
    vec2 magnified = rectCenter + (fragScreen - rectCenter) * 0.90;
    vec2 wave = vec2(
        sin(local.y * 24.0 + local.x * 7.0) * 9.0,
        sin(local.x * 18.0 + local.y * 5.0) * 5.0
    );
    vec2 sampleScreen = magnified + wave * shapeAlpha;
    vec2 uv = clamp((sampleScreen - pc.textureOrigin) / pc.textureSize, vec2(0.0), vec2(1.0));
    vec2 texel = 1.0 / max(pc.textureSize, vec2(1.0));
    vec2 blurRadius = texel * 8.0;

    vec3 color = texture(backdropTex, uv).rgb * 0.34;
    color += texture(backdropTex, uv + vec2(blurRadius.x, 0.0)).rgb * 0.11;
    color += texture(backdropTex, uv - vec2(blurRadius.x, 0.0)).rgb * 0.11;
    color += texture(backdropTex, uv + vec2(0.0, blurRadius.y)).rgb * 0.11;
    color += texture(backdropTex, uv - vec2(0.0, blurRadius.y)).rgb * 0.11;
    color += texture(backdropTex, uv + blurRadius).rgb * 0.055;
    color += texture(backdropTex, uv - blurRadius).rgb * 0.055;
    color += texture(backdropTex, uv + vec2(blurRadius.x, -blurRadius.y)).rgb * 0.055;
    color += texture(backdropTex, uv + vec2(-blurRadius.x, blurRadius.y)).rgb * 0.055;

    float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
    color = mix(vec3(luma), color, 1.28);
    color = mix(color, vec3(0.55, 1.0, 0.94), 0.42);

    float edgeAlpha = 1.0 - roundedRectAlpha(fragScreen, pc.rect, max(pc.cornerRadius - 1.5, 0.0));
    color += vec3(edgeAlpha * 0.26);

    outColor = vec4(clamp(color, 0.0, 1.0), shapeAlpha * pc.opacity);
}
