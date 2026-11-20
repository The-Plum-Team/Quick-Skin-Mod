package com.quickskin.mod.client.immersive;

//? if <1.21.6 {
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.stream.Collectors;

//? if >=1.21.5 {
import com.mojang.blaze3d.opengl.GlTexture;
//?}
//?}

/** Snapshots the primary camera and blends the clipped terrain into its circular aperture. */
final class LegacyOpeningTarget implements AutoCloseable {
    //? if <1.21.6 {
    private static final int DEPTH = GL11.GL_DEPTH_BUFFER_BIT;
    private static final int COLOR = GL11.GL_COLOR_BUFFER_BIT;
    private final int world = GL30.glGenFramebuffers();
    private final int scene = GL30.glGenFramebuffers();
    private final int terrain = GL30.glGenFramebuffers();
    private final int color = GL11.glGenTextures();
    private final int depth = GL11.glGenTextures();
    private final int terrainDepth = GL11.glGenTextures();
    private final int cells = GL11.glGenTextures();
    private final int sectionDepth = GL11.glGenTextures();
    private final int section = GL30.glGenFramebuffers();
    private final int array = GL30.glGenVertexArrays();
    private final int program;
    private int width, height, attachedColor, attachedDepth;
    private int uClip, uGrid, uFloor, uBreath, uOpening;

    static LegacyOpeningTarget create() throws IOException {
        //? if <1.21 {
        var location = new ResourceLocation("quickskin", "shaders/core/immersive_block_reveal.fsh");
        //?} else {
        var location = ResourceLocation.fromNamespaceAndPath("quickskin", "shaders/core/immersive_block_reveal.fsh");
        //?}
        try (BufferedReader reader = Minecraft.getInstance().getResourceManager().openAsReader(location)) {
            return new LegacyOpeningTarget(reader.lines().collect(Collectors.joining("\n")));
        }
    }

    private LegacyOpeningTarget(String fragment) {
        try {
            program = fullscreenProgram(fragment);
            int previous = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            GL20.glUseProgram(program);
            try {
                for (int i = 0; i < 4; i++) GL20.glUniform1i(uniform("Sampler" + i), i);
                uClip = uniform("FromClip");
                uGrid = uniform("CameraInGrid");
                uFloor = uniform("FloorY");
                uBreath = uniform("Breath");
                uOpening = uniform("Opening");
            } finally {
                GL20.glUseProgram(previous);
            }
        } catch (RuntimeException failure) {
            deleteTargets();
            throw failure;
        }
    }

    static int fullscreenProgram(String fragment) {
        int linked = GL20.glCreateProgram();
        int vertex = 0, pixel = 0;
        try {
            vertex = compile(GL20.GL_VERTEX_SHADER, """
                    #version 150
                    void main() {
                        vec2 position = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                        gl_Position = vec4(position * 2.0 - 1.0, 0.0, 1.0);
                    }
                    """);
            pixel = compile(GL20.GL_FRAGMENT_SHADER, fragment);
            GL20.glAttachShader(linked, vertex);
            GL20.glAttachShader(linked, pixel);
            GL20.glLinkProgram(linked);
            if (GL20.glGetProgrami(linked, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                throw new IllegalStateException(GL20.glGetProgramInfoLog(linked));
            }
            return linked;
        } catch (RuntimeException failure) {
            GL20.glDeleteProgram(linked);
            throw failure;
        } finally {
            if (vertex != 0) GL20.glDeleteShader(vertex);
            if (pixel != 0) GL20.glDeleteShader(pixel);
        }
    }

    private int uniform(String name) {
        return GL20.glGetUniformLocation(program, name);
    }

    private static int compile(int type, String source) {
        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, source);
        GL20.glCompileShader(id);
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(id);
            GL20.glDeleteShader(id);
            throw new IllegalStateException(log);
        }
        return id;
    }

    /** Called in the main camera's terrain stage; Iris can have its G-buffer bound here. */
    void terrain(RenderTarget target, ImmersiveOpening opening) {
        try (Bindings ignored = new Bindings()) {
            ensureSize(target);
            blit(world, terrain, DEPTH, opening);
        }
    }

    void snapshot(RenderTarget target) {
        try (Bindings ignored = new Bindings()) {
            ensureSize(target);
            blit(world, scene, COLOR | DEPTH, null);
        }
    }

    /** Shader pipelines can finish their world pass with an intermediate draw framebuffer active. */
    void selectWorld(RenderTarget target) {
        //? if <1.21.5 {
        target.bindWrite(false);
        //?} else {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, world);
        //?}
    }

    void restore(boolean colorToo) {
        try (Bindings ignored = new Bindings()) {
            blit(scene, world, colorToo ? COLOR | DEPTH : DEPTH, null);
        }
    }

    /** A terrain section closes a wall in colour. Keep the original depths for overlapping limbs. */
    void drawSection(ImmersiveOpening opening) {
        try (Bindings ignored = new Bindings()) {
            blit(world, section, DEPTH, opening);
        }
        try {
            LegacyTerrainGeometry.flushSection();
        } finally {
            try (Bindings ignored = new Bindings()) {
                blit(section, world, DEPTH, opening);
            }
        }
    }

    private void ensureSize(RenderTarget target) {
        //? if <1.21.5 {
        int worldColor = target.getColorTextureId(), worldDepth = target.getDepthTextureId();
        //?} else {
        int worldColor = ((GlTexture)target.getColorTexture()).glId(), worldDepth = ((GlTexture)target.getDepthTexture()).glId();
        //?}
        if (worldColor == attachedColor && worldDepth == attachedDepth && target.width == width && target.height == height) return;
        width = target.width; height = target.height;
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, worldColor);
        int colorFormat = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, worldDepth);
        int depthFormat = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
        boolean stencil = depthFormat == GL30.GL_DEPTH24_STENCIL8 || depthFormat == GL30.GL_DEPTH32F_STENCIL8
                || depthFormat == GL30.GL_DEPTH_STENCIL;
        int format = stencil ? GL30.GL_DEPTH_STENCIL : GL11.GL_DEPTH_COMPONENT;
        int type = stencil ? (depthFormat == GL30.GL_DEPTH32F_STENCIL8 ? GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV : GL30.GL_UNSIGNED_INT_24_8) : GL11.GL_FLOAT;
        allocate(color, width, height, colorFormat, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE);
        allocate(depth, width, height, depthFormat, format, type);
        allocate(terrainDepth, width, height, depthFormat, format, type);
        allocate(sectionDepth, width, height, depthFormat, format, type);
        allocate(cells, ImmersiveOpening.SIDE * ImmersiveOpening.SIDE, ImmersiveOpening.SIDE, GL30.GL_RG8, GL30.GL_RG, GL11.GL_UNSIGNED_BYTE);
        attach(world, worldColor, worldDepth, stencil);
        attach(scene, color, depth, stencil);
        attach(terrain, 0, terrainDepth, stencil);
        attach(section, 0, sectionDepth, stencil);
        attachedColor = worldColor; attachedDepth = worldDepth;
    }

    private static void allocate(int texture, int w, int h, int internal, int format, int type) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, 0L);
    }

    private static void attach(int fbo, int color, int depth, boolean stencil) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color, 0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depth, 0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_STENCIL_ATTACHMENT, GL11.GL_TEXTURE_2D, stencil ? depth : 0, 0);
        GL11.glReadBuffer(color == 0 ? GL11.GL_NONE : GL30.GL_COLOR_ATTACHMENT0);
        GL11.glDrawBuffer(color == 0 ? GL11.GL_NONE : GL30.GL_COLOR_ATTACHMENT0);
        if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Incomplete terrain opening framebuffer");
        }
    }

    private void blit(int source, int destination, int mask, ImmersiveOpening area) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, destination);
        int x = area == null ? 0 : area.left(), y = area == null ? 0 : area.bottom();
        int r = area == null ? width : x + area.width(), t = area == null ? height : y + area.height();
        GL30.glBlitFramebuffer(x, y, r, t, x, y, r, t, mask, GL11.GL_NEAREST);
    }

    void composite(ImmersiveOpening opening, boolean terrainCaptured) {
        int oldProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int oldArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND), test = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK), cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        int src = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dst = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int srcA = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstA = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        int eq = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB), eqA = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        try (Bindings ignored = new Bindings(); MemoryStack memory = MemoryStack.stackPush()) {
            int[] previous = new int[4];
            for (int i = 0; i < previous.length; i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                previous[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            }
            try {
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, world);
                GL11.glViewport(0, 0, width, height);
                GL20.glUseProgram(program);
                GL30.glBindVertexArray(array);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glDepthMask(false);
                GL11.glEnable(GL11.GL_BLEND);
                GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
                GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
                GL20.glUniformMatrix4fv(uClip, false, opening.fromClip().get(memory.mallocFloat(16)));
                var origin = opening.cameraInGrid();
                GL20.glUniform3f(uGrid, (float)origin.x, (float)origin.y, (float)origin.z);
                GL20.glUniform1f(uFloor, opening.floor());
                GL20.glUniform1f(uBreath, (float)Math.sin((System.nanoTime() % 6_000_000_000L) * (Math.PI / 3_000_000_000.0)));
                GL20.glUniform4f(uOpening, opening.x(), opening.y(), opening.radius(), opening.cut());
                bind(0, color); bind(1, depth); bind(2, cells);
                ByteBuffer pixels = memory.malloc(opening.cells().length * 2);
                for (byte bits : opening.cells()) {
                    pixels.put((byte)((bits & ImmersiveOpening.OCCUPIED) == 0 ? 0 : 255));
                    pixels.put((byte)((bits & ImmersiveOpening.LATE) == 0 ? 0 : 255));
                }
                pixels.flip();
                uploadCells(pixels);
                bind(3, terrainCaptured ? terrainDepth : depth);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            } finally {
                for (int i = 0; i < previous.length; i++) bind(i, previous[i]);
                enabled(GL11.GL_CULL_FACE, cull); enabled(GL11.GL_DEPTH_TEST, test); enabled(GL11.GL_BLEND, blend);
                GL11.glDepthMask(depthWrite);
                GL20.glBlendEquationSeparate(eq, eqA); GL14.glBlendFuncSeparate(src, dst, srcA, dstA);
                GL30.glBindVertexArray(oldArray); GL20.glUseProgram(oldProgram);
            }
        }
    }

    /** Atlas and animated sprite uploads can leave a row stride and source offset behind. */
    private static void uploadCells(ByteBuffer pixels) {
        int rowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
        int skipRows = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS);
        int skipPixels = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
        int unpackBuffer = GL11.glGetInteger(GL30.GL_PIXEL_UNPACK_BUFFER_BINDING);
        try {
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GL15.glBindBuffer(GL30.GL_PIXEL_UNPACK_BUFFER, 0);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, ImmersiveOpening.SIDE * ImmersiveOpening.SIDE,
                    ImmersiveOpening.SIDE, GL30.GL_RG, GL11.GL_UNSIGNED_BYTE, pixels);
        } finally {
            GL15.glBindBuffer(GL30.GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, rowLength);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, skipRows);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, skipPixels);
        }
    }

    private static void bind(int unit, int texture) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
    }

    private static void enabled(int capability, boolean value) {
        if (value) GL11.glEnable(capability); else GL11.glDisable(capability);
    }

    private static final class Bindings implements AutoCloseable {
        private final int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        private final int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        private final int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        private final int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        private final boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        private final int[] viewport = new int[4];
        Bindings() {
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
        public void close() {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read); GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            GL13.glActiveTexture(active); GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]); enabled(GL11.GL_SCISSOR_TEST, scissor);
        }
    }

    private void deleteTargets() {
        GL30.glDeleteFramebuffers(world); GL30.glDeleteFramebuffers(scene); GL30.glDeleteFramebuffers(terrain);
        GL11.glDeleteTextures(color); GL11.glDeleteTextures(depth); GL11.glDeleteTextures(terrainDepth); GL11.glDeleteTextures(cells);
        GL11.glDeleteTextures(sectionDepth); GL30.glDeleteFramebuffers(section);
        GL30.glDeleteVertexArrays(array);
    }

    @Override public void close() {
        deleteTargets();
        GL20.glDeleteProgram(program);
    }
    //?} else {
    public void close() {}
    //?}
}
