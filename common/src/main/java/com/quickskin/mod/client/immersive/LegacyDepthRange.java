package com.quickskin.mod.client.immersive;

//? if <1.21.6 {
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
//?}

/** Returns shader-pack depth to the original camera projection after the pack has finished shading. */
public final class LegacyDepthRange {
    //? if <1.21.6 {
    private static final String FRAGMENT = """
            #version 150
            uniform sampler2D ClippedDepth;
            uniform vec2 DepthRange;
            void main() {
                float depth = texelFetch(ClippedDepth, ivec2(gl_FragCoord.xy), 0).r;
                gl_FragDepth = mix(depth, DepthRange.y, DepthRange.x);
            }
            """;
    private static int program, texture, vertexArray, depthRange;
    private static int width, height;

    /** The caller has bound the world's color and depth target and finished Iris' post-processing. */
    public static void restore(ImmersiveOpening opening, double farDepth) {
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        boolean test = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        boolean depthWrites = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int function = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var colorMask = stack.malloc(4);
            var box = stack.mallocInt(4);
            var viewport = stack.mallocInt(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, colorMask);
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, box);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            try {
                ensureTarget();
                int left = opening.left(), bottom = opening.bottom();
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, draw);
                GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, left, bottom,
                        left, bottom, opening.width(), opening.height());
                GL20.glUseProgram(program);
                GL30.glBindVertexArray(vertexArray);
                GL20.glUniform2f(depthRange, opening.cut(), (float)farDepth);
                GL11.glViewport(0, 0, width, height);
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
                GL11.glScissor(left, bottom, opening.width(), opening.height());
                GL11.glDisable(GL11.GL_STENCIL_TEST);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glColorMask(false, false, false, false);
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthFunc(GL11.GL_ALWAYS);
                GL11.glDepthMask(true);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            } finally {
                GL11.glDepthMask(depthWrites);
                GL11.glDepthFunc(function);
                if (!test) GL11.glDisable(GL11.GL_DEPTH_TEST);
                if (cull) GL11.glEnable(GL11.GL_CULL_FACE);
                if (stencil) GL11.glEnable(GL11.GL_STENCIL_TEST);
                GL11.glColorMask(colorMask.get(0) != 0, colorMask.get(1) != 0,
                        colorMask.get(2) != 0, colorMask.get(3) != 0);
                GL11.glScissor(box.get(0), box.get(1), box.get(2), box.get(3));
                if (!scissor) GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
                GL30.glBindVertexArray(previousArray);
                GL20.glUseProgram(previousProgram);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
                GL13.glActiveTexture(previousUnit);
            }
        }
    }

    private static void ensureTarget() {
        if (program == 0) {
            program = LegacyOpeningTarget.fullscreenProgram(FRAGMENT);
            depthRange = GL20.glGetUniformLocation(program, "DepthRange");
            vertexArray = GL30.glGenVertexArrays();
            texture = GL11.glGenTextures();
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        var target = Minecraft.getInstance().getMainRenderTarget();
        if (width == target.width && height == target.height) return;
        width = target.width;
        height = target.height;
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT32F, width, height,
                0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, 0L);
    }

    static void release() {
        if (program == 0) return;
        GL11.glDeleteTextures(texture);
        GL30.glDeleteVertexArrays(vertexArray);
        GL20.glDeleteProgram(program);
        program = 0;
        width = 0;
        height = 0;
    }
    //?}
    private LegacyDepthRange() {}
}
