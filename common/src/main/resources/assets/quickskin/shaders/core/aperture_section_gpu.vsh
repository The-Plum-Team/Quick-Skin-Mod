#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;
layout(location = 0) out vec2 qsUv;
layout(location = 1) out vec4 qsTint;
layout(location = 2) out vec4 qsClip;

layout(std140) uniform QuickSkinAperture {
    mat4 qsFromClip;
    vec4 qsAperture;
    vec4 qsWorld;
};
void main() {
    qsUv = UV0;
    qsTint = Color;
    qsClip = inverse(qsFromClip) * vec4(Position, 1.0);
    gl_Position = qsClip;
}
