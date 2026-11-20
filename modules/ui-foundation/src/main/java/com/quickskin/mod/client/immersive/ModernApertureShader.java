package com.quickskin.mod.client.immersive;

import java.util.regex.Pattern;

/** Adds the aperture variant to the terrain source selected by the game's resource manager. */
public final class ModernApertureShader {
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)");

    private ModernApertureShader() {}

    /** Vanilla model and font programs that can carry a block entity's light coordinates. */
    public static boolean isBlockMaterial(String path) {
        return switch (path) {
            case "core/entity", "core/block", "core/item", "core/text", "core/text_background",
                    "core/rendertype_text", "core/rendertype_text_intensity", "core/rendertype_text_background",
                    "core/rendertype_entity_decal", "core/rendertype_item_entity_translucent_cull",
                    "core/rendertype_translucent_moving_block" -> true;
            default -> false;
        };
    }

    public static String wrapBlockMaterial(String source, boolean vertex, boolean explicitLocations) {
        var main = MAIN.matcher(source);
        if (!main.find()) return source;
        String guard = "#if defined(QUICKSKIN_BLOCK_ENTITY) && !defined(IS_GUI) && !defined(IS_SEE_THROUGH)\n";
        String location = explicitLocations ? "layout(location = 8) " : "";
        StringBuilder header = new StringBuilder(guard);
        if (vertex) {
            if (!source.contains("UV2")) return source;
            header.append("bool qsBlockVertex() { return all(equal(UV2 & ivec2(15), ivec2(2, 5))); }\n");
            header.append("#define UV2 (qsBlockVertex() ? (UV2 & ~ivec2(15)) : UV2)\n");
        }
        header.append("#define main qsOriginalBlockMain\n#endif\n");
        String code = vertex ? "#undef UV2\n" + location + "out vec4 qsBlockClip;\n"
                + "void main() { qsOriginalBlockMain(); qsBlockClip = qsBlockVertex() ? gl_Position : vec4(0.0); }\n"
                : location + "in vec4 qsBlockClip;\n" + fragmentTest()
                + "void main() { if (qsClipTerrain(qsBlockClip)) discard; qsOriginalBlockMain(); }\n";
        return source.substring(0, main.start()) + "\n" + header + source.substring(main.start())
                + "\n" + guard + "#undef main\n" + code + "#endif\n";
    }

    /** Depth is measured in view space. The same clip coordinates work with reversed Z and Vulkan. */
    public static String fragmentTest() {
        return fragmentTest(true);
    }

    public static String fragmentTest(boolean uniformBlock) {
        String uniforms = uniformBlock ? """
                layout(std140) uniform QuickSkinAperture {
                    mat4 qsFromClip;
                    vec4 qsAperture;
                    vec4 qsWorld;
                };
                """ : "uniform mat4 qsFromClip;\nuniform vec4 qsAperture;\nuniform vec4 qsWorld;\n";
        return uniforms + """
                float qsBayer2(vec2 cell) {
                    vec2 bit = mod(cell, 2.0);
                    return 2.0 * mod(bit.x + bit.y, 2.0) + bit.y;
                }
                bool qsClearPixel(vec4 clip) {
                    if (qsAperture.z <= 0.0 || clip.w <= 0.0) return false;
                    vec2 pixel = (clip.xy / clip.w * 0.5 + 0.5) * qsWorld.xy;
                    float radius = distance(pixel, qsAperture.xy) / qsAperture.z;
                    if (radius >= 1.0) return false;
                    float fade = smoothstep(0.72 + qsWorld.w * 0.01, 1.0, radius);
                    vec2 cell = floor(pixel);
                    float threshold = (16.0 * qsBayer2(cell) + 4.0 * qsBayer2(floor(cell / 2.0))
                                       + qsBayer2(floor(cell / 4.0)) + 0.5) / 64.0;
                    return fade < threshold;
                }
                bool qsClipTerrain(vec4 clip) {
                    if (clip.w >= qsAperture.w || !qsClearPixel(clip)) return false;
                    vec4 point = qsFromClip * clip;
                    return point.y > (qsWorld.z + 0.001) * point.w;
                }
                """;
    }

    /** Leaves the originally selected terrain shader intact for ordinary pipelines and resource packs. */
    public static String wrap(String source, boolean vertex, boolean explicitLocations) {
        var main = MAIN.matcher(source);
        if (!main.find()) return source;
        String location = explicitLocations ? "layout(location = 7) " : "";
        String original = source.substring(0, main.start()) + "\n#ifdef QUICKSKIN_APERTURE\n"
                + "#define main qsOriginalTerrainMain\n#endif\n" + source.substring(main.start());
        String code = vertex ? location + "out vec4 qsClip;\n"
                + "void main() { qsOriginalTerrainMain(); qsClip = gl_Position; }\n"
                : location + "in vec4 qsClip;\n" + fragmentTest()
                + "void main() { if (qsClipTerrain(qsClip)) discard; qsOriginalTerrainMain(); }\n";
        return original + "\n#ifdef QUICKSKIN_APERTURE\n#undef main\n" + code + "#endif\n";
    }
}
