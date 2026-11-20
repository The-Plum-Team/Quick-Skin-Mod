#version 150

in vec3 Position;
in vec2 UV0;
in vec4 Color;
out vec2 qsUv;
out vec4 qsTint;
out vec4 qsClip;

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
