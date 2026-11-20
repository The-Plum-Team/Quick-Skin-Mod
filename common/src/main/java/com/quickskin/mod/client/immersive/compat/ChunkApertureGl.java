package com.quickskin.mod.client.immersive.compat;

//? if >=1.21.6 {
import com.quickskin.mod.client.immersive.ImmersiveOpening;
import com.quickskin.mod.client.immersive.ImmersiveShaderPacks;
import com.quickskin.mod.client.immersive.ModernAperture;
import com.quickskin.mod.client.immersive.ModernApertureShader;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
//?}

/** The terrain shaders that Sodium and Iris compile and bind themselves on OpenGL. */
public final class ChunkApertureGl {
    //? if >=1.21.6 {
    // Preserve comments (including examples of main) and preprocessor branches in pack sources.
    private static final Pattern MAIN = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\n]*|\\bvoid\\s+(main)\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{");
    private int aperture, fromClip, world, viewport, clipControl;
    private boolean inspected;
    private ImmersiveOpening uploaded;

    public static boolean sodiumTerrain(String name) {
        return name.equals("sodium:blocks/block_layer_opaque.fsh")
                || name.equals("iris:sodium-shader-terrain") || name.equals("iris:sodium-shader-terrain_cutout")
                || name.equals("iris:sodium-shader-translucent");
    }

    public static boolean irisTerrain(String name) {
        return switch (name) {
            case "terrain_solid", "terrain_cutout", "terrain_translucent",
                    "sodium_terrain_solid", "sodium_terrain_cutout", "sodium_terrain_translucent" -> true;
            default -> false;
        };
    }

    public static String wrap(String source) {
        if (source == null) return null;
        Matcher match = MAIN.matcher(source);
        StringBuilder result = new StringBuilder(source.length() + 1900);
        int last = 0;
        while (match.find()) {
            if (match.start(1) < 0) continue;
            result.append(source, last, match.start(1)).append("qsOriginalTerrainMain");
            last = match.end(1);
        }
        if (last == 0) return source;
        // gl_FragCoord retains the final clip depth and reciprocal W even for terrain packs
        // with geometry or tessellation stages. No extra varyings have to cross those stages.
        return result.append(source, last, source.length()).append('\n')
                .append(ModernApertureShader.fragmentTest(false)).append("""
                    uniform vec4 qsViewport;
                    uniform vec2 qsClipControl;
                    void main() {
                        if (qsAperture.w > 0.0) {
                            vec2 xy = (gl_FragCoord.xy - qsViewport.xy) / qsViewport.zw * 2.0 - 1.0;
                            xy.y *= qsClipControl.x;
                            float depth = (gl_FragCoord.z - gl_DepthRange.near) / gl_DepthRange.diff;
                            float z = mix(depth * 2.0 - 1.0, depth, qsClipControl.y);
                            if (qsClipTerrain(vec4(xy, z, 1.0) / gl_FragCoord.w)) discard;
                        }
                        qsOriginalTerrainMain();
                    }
                    """).toString();
    }

    /** Called with this program in use, after Sodium or Iris binds its own uniforms. */
    public void bound() {
        if (!inspected) {
            int program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
            if (program == 0) return;
            aperture = GL20C.glGetUniformLocation(program, "qsAperture");
            if (aperture >= 0) {
                fromClip = GL20C.glGetUniformLocation(program, "qsFromClip");
                world = GL20C.glGetUniformLocation(program, "qsWorld");
                viewport = GL20C.glGetUniformLocation(program, "qsViewport");
                clipControl = GL20C.glGetUniformLocation(program, "qsClipControl");
            }
            inspected = true;
        }
        if (aperture < 0) return;
        ImmersiveOpening opening = ModernAperture.opening();
        if (opening != null && ImmersiveShaderPacks.shadows()) opening = null;
        if (opening == uploaded) return;
        uploaded = opening;
        if (opening == null) {
            GL20C.glUniform4f(aperture, 0, 0, 0, 0);
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = stack.malloc(96);
            ModernAperture.fillUniforms(data);
            GL20C.glUniformMatrix4fv(fromClip, false, data.asFloatBuffer().limit(16));
            GL20C.glUniform4f(aperture, data.getFloat(64), data.getFloat(68), data.getFloat(72), data.getFloat(76));
            GL20C.glUniform4f(world, data.getFloat(80), data.getFloat(84), data.getFloat(88), data.getFloat(92));
            var box = stack.mallocInt(4);
            GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, box);
            GL20C.glUniform4f(viewport, box.get(0), box.get(1), box.get(2), box.get(3));
            var caps = GL.getCapabilities();
            boolean controlled = caps.OpenGL45 || caps.GL_ARB_clip_control;
            boolean topLeft = controlled && GL11C.glGetInteger(GL45C.GL_CLIP_ORIGIN) == GL20C.GL_UPPER_LEFT;
            boolean zeroToOne = controlled && GL11C.glGetInteger(GL45C.GL_CLIP_DEPTH_MODE) == GL45C.GL_ZERO_TO_ONE;
            GL20C.glUniform2f(clipControl, topLeft ? -1 : 1, zeroToOne ? 1 : 0);
        }
    }
    //?}
}
