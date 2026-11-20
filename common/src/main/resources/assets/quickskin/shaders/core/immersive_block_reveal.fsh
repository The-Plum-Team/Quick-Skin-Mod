#version 150
uniform sampler2D Sampler0; // Normal world colour
uniform sampler2D Sampler1; // Normal world depth
uniform sampler2D Sampler2; // Red: occupied cells for resource packs. Green: late-drawn block cells.
uniform sampler2D Sampler3; // Depth of solid and cutout terrain, before entities
uniform float FloorY; // Player support plane, relative to the camera
uniform mat4 FromClip;
uniform vec3 CameraInGrid;
uniform vec4 Opening; // Centre and outer radius in framebuffer pixels, then the depth cut
uniform float Breath;
out vec4 fragColor;

bool containsLateBlock(vec3 position) {
    ivec3 cell = ivec3(floor(position));
    if (any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, ivec3(16)))) return false;
    return texelFetch(Sampler2, ivec2(cell.x + 16 * cell.z, cell.y), 0).g >= 0.5;
}

const int bayer[16] = int[16](0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5);

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    fragColor = vec4(texelFetch(Sampler0, pixel, 0).rgb, 1.0);
    float radius = distance(gl_FragCoord.xy, Opening.xy) / Opening.z;
    // The clear core stays still. Only the feather width breathes.
    float outer = 0.993 + 0.007 * Breath;
    if (radius >= outer) return;
    float scene = texelFetch(Sampler1, pixel, 0).r;
    if (scene >= Opening.w) return;
    vec2 uv = gl_FragCoord.xy / vec2(textureSize(Sampler1, 0));
    vec4 point = FromClip * vec4(uv * 2.0 - 1.0, scene * 2.0 - 1.0, 1.0);
    vec3 relative = point.xyz / point.w;
    vec3 face = CameraInGrid + relative;
    vec3 stepIntoFace = normalize(relative) * 0.002;
    if (FloorY < 0.0 && relative.y <= FloorY + 0.001) return;
    float terrain = texelFetch(Sampler3, pixel, 0).r;
    if (abs(terrain - scene) > 0.00000012
            && !containsLateBlock(face + stepIntoFace) && !containsLateBlock(face - stepIntoFace)) return;

    if (radius <= 0.76) discard;
    float opacity = smoothstep(0.76, outer, radius);
    ivec2 tile = pixel % 4;
    float dither = (float(bayer[tile.x + 4 * tile.y]) + 0.5) / 16.0 - 0.5;
    fragColor.a = opacity + dither * (0.12 * opacity * (1.0 - opacity));
}
