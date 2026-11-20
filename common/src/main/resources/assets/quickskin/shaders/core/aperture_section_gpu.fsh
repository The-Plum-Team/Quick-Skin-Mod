#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec2 qsUv;
layout(location = 1) in vec4 qsTint;
layout(location = 2) in vec4 qsClip;
layout(location = 0) out vec4 fragColor;

uniform sampler2D Sampler0;
#define QUICKSKIN_APERTURE_FUNCTIONS
void main() {
    if (qsTint.a > 0.9) {
        if (qsClipTerrain(qsClip)) discard;
    } else {
        if (!qsClearPixel(qsClip)) discard;
        if (qsTint.a < 0.4 && qsClip.w > qsAperture.w) discard;
    }
    vec4 texel = texture(Sampler0, qsUv);
    if (texel.a < 0.1) discard;
    fragColor = vec4(texel.rgb * qsTint.rgb, 1.0);
}
