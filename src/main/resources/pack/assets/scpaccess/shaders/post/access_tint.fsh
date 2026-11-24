#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform TintConfig {
    vec4 Tint;
    float Strength;
    float Scan;
};

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 c = texture(InSampler, texCoord);
    float edge = smoothstep(0.30, 0.95, length(texCoord - 0.5) * 1.6);
    float lines = 0.5 + 0.5 * sin(texCoord.y * OutSize.y * 1.4);
    vec3 col = mix(c.rgb, c.rgb * (0.55 + Tint.rgb), Strength * 0.6);
    col += Tint.rgb * edge * Strength * 0.9;
    col *= 1.0 - Scan * (1.0 - lines) * 0.30;
    fragColor = vec4(col, 1.0);
}
