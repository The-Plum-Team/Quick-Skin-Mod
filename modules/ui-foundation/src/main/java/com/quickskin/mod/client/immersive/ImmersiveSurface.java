package com.quickskin.mod.client.immersive;

//? if <1.21.6 {
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.jetbrains.annotations.Nullable;
//?}
//? if <1.21 {
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
//?} else if <1.21.2 {
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
//?} else if <1.21.6 {
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
    //? if <1.21.5 {
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.BufferUploader;
import net.minecraft.client.renderer.CoreShaders;
    //?} else {
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.resources.ResourceLocation;

import java.util.OptionalInt;
    //?}
//?} else if <1.21.9 {
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import org.jetbrains.annotations.Nullable;
//?} else if <26.1 {
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import org.jetbrains.annotations.Nullable;
//?} else {
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.jetbrains.annotations.Nullable;
    //? if <26.2 {
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.FilterMode;
    //?} else if <26.3 {
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.FilterMode;
import org.joml.Vector4f;
    //?} else {
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import org.joml.Vector4f;
    //?}
//?}

/**
 * The picture of the screen that floats in the immersive view: the screen is drawn into a texture
 * of its own instead of over the world, and that texture is then drawn where each of its points
 * stands this frame ({@link ImmersiveFrame}). The mesh is worked out on the CPU, in screen space
 * ({@link ImmersiveMesh}), so it is drawn like any other GUI element, after the world and whatever
 * shaders drew of it.
 *
 * <p>How the screen gets into its picture depends on how the game draws a GUI:
 * <ul>
 *   <li>Before 1.21.6 a screen draws at once, so the GUI draws into the picture while the screen
 *   draws ({@link #begin}, {@link #beginAbove}, {@link #end}) and the picture is drawn over the
 *   world before the toasts ({@link #draw}). Each version family gives the same few GPU primitives
 *   below ({@code sized}, {@code drawInto}, {@code backToTheGame}, {@code startMesh},
 *   {@code vertex}, {@code endMesh}, {@code drawState}, {@code gameState}): 1.20.1 and 1.21.1 bind
 *   the picture's target, 1.21.2 to 1.21.5 have the game answer it as its main target
 *   ({@code drawingInto}), and 1.21.5 draws the mesh with a render pass of its own.</li>
 *   <li>From 1.21.6 a screen only extracts what it draws and the game draws the whole GUI once,
 *   later: the picture is drawn by a GUI pass of its own, run right after the screen's own call
 *   ({@link #drawScreenPass}), and the mesh is then handed to the frame's GUI state as one element.
 *   From 26.1 that pass runs while the frame is extracted, before any of it is drawn.</li>
 * </ul>
 * Every supported version fills its family's block, and {@link #SUPPORTED} is true on all of them.
 */
public final class ImmersiveSurface {
    /** Whether the screen is being drawn into its own picture now. */
    private static boolean capturing;
    /** Whether a picture of the screen has been drawn since the view started. */
    private static boolean captured;
    /** Whether the screen drew something above itself into the second picture this frame. */
    private static boolean aboveCaptured;
    /**
     * Whether the screen last drawn into the picture is a dialog over a floating screen, which
     * left only that screen in the picture and drew itself flat over it ({@link #parentDrawn}).
     */
    private static boolean parentApart;

    private ImmersiveSurface() {
    }

    /** Whether the screen is being drawn into its own picture now. */
    public static boolean capturing() {
        return capturing;
    }

    /**
     * Whether the screen last drawn is a dialog that drew itself flat over the floating screen it
     * opened from ({@link #parentDrawn}): that screen then keeps its panels where they swing and
     * the mouse points at the dialog where it is.
     */
    public static boolean parentApart() {
        return parentApart;
    }

//? if <1.21.6 {
    @Nullable
    private static TextureTarget target;
    /** What the screen draws above itself, such as a tooltip, which is floated flat at the mouse. */
    @Nullable
    private static TextureTarget above;
    /** Whether a dialog drew the picture under itself this frame ({@link #parentDrawn}), so the frame's later draw is skipped. */
    private static boolean drawnEarly;

    /** From here on the screen draws into its own picture. */
    public static void begin(GuiGraphics graphics) {
        Window window = Minecraft.getInstance().getWindow();
        int width = Math.max(1, window.getWidth());
        int height = Math.max(1, window.getHeight());
        target = sized(target, width, height, true);
        graphics.flush();
        drawInto(target);
        capturing = true;
        aboveCaptured = false;
        parentApart = false;
        drawnEarly = false;
    }

    /**
     * A dialog has drawn the floating screen it opened from as its background: only that screen
     * goes into the picture, which floats at once, and the dialog draws itself, and its tooltip,
     * flat over it on the game's own target. The screen behind keeps its panels where they swing,
     * and the dialog is never cut where they meet.
     */
    public static void parentDrawn(GuiGraphics graphics) {
        if (!capturing || target == null) return;
        graphics.flush();
        capturing = false;
        captured = true;
        parentApart = true;
        backToTheGame();
        ImmersiveMode.drawOverlay();
        drawnEarly = true;
    }

    /**
     * From here on the screen draws what lies above it and follows the mouse: its tooltip. That
     * goes into a second picture, which is floated flat beside the mouse, so a tooltip is never
     * cut where it leaves the panel it belongs to.
     */
    public static void beginAbove(GuiGraphics graphics) {
        if (!capturing || target == null) return;
        above = sized(above, target.width, target.height, false);
        graphics.flush();
        drawInto(above);
        aboveCaptured = true;
    }

    /** The screen is drawn: the game draws over the world again. */
    public static void end(GuiGraphics graphics) {
        if (!capturing) return;
        capturing = false;
        captured = true;
        graphics.flush();
        backToTheGame();
    }

    /**
     * Draws the last picture of the screen where {@code frame} stands it, in a GUI of
     * {@code guiWidth} by {@code guiHeight} units, and what the screen drew above itself flat,
     * moved by {@code (aboveX, aboveY)} screen units.
     */
    public static void draw(ImmersiveFrame frame, float alpha, float guiWidth, float guiHeight,
                            float aboveX, float aboveY) {
        if (drawnEarly) {
            // A dialog drew the picture under itself already ({@link #parentDrawn})
            drawnEarly = false;
            return;
        }
        if (target == null || !captured || alpha <= 0.0f) return;
        // Whatever the GUI still holds back is drawn first, under the floating screen
        Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
        drawState();
        drawMesh(target, ImmersiveMesh.sheet(frame, guiWidth, guiHeight), alpha);
        // What the screen drew above itself, moved from where the mouse points to where the mouse is
        if (aboveCaptured && above != null && alpha >= 1.0f) {
            drawMesh(above, ImmersiveMesh.above(frame, guiWidth, guiHeight, aboveX, aboveY), 1.0f);
        }
        gameState();
    }

    /** Draws {@code vertices} ({@link ImmersiveMesh}) with {@code picture}, its colours faded by {@code alpha}. */
    private static void drawMesh(TextureTarget picture, float[] vertices, float alpha) {
        startMesh(picture);
        for (int i = 0; i < vertices.length; i += ImmersiveMesh.STRIDE) {
            vertex(vertices[i], vertices[i + 1], vertices[i + 2], vertices[i + 3], alpha);
        }
        endMesh();
    }

    //? if <1.21 {
    /** Whether this Minecraft version can float a screen. */
    public static final boolean SUPPORTED = true;
    /** The mesh being built. */
    @Nullable
    private static BufferBuilder builder;

    /** {@code current} at the window's size, made or resized as needed, with linear filtering if asked. */
    private static TextureTarget sized(@Nullable TextureTarget current, int width, int height, boolean linear) {
        if (current == null) {
            current = new TextureTarget(width, height, true, Minecraft.ON_OSX);
            if (linear) {
                current.setFilterMode(org.lwjgl.opengl.GL11.GL_LINEAR);
            }
        } else if (current.width != width || current.height != height) {
            current.resize(width, height, Minecraft.ON_OSX);
            if (linear) {
                current.setFilterMode(org.lwjgl.opengl.GL11.GL_LINEAR);
            }
        }
        return current;
    }

    /** Clears {@code picture} to nothing and has the GUI draw into it. */
    private static void drawInto(TextureTarget picture) {
        picture.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        picture.clear(Minecraft.ON_OSX);
        picture.bindWrite(true);
    }

    /** Has the GUI draw into the game's own target again. */
    private static void backToTheGame() {
        Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
    }

    /** How the pictures are drawn: their colours are already multiplied by their alpha; no depth, both faces. */
    private static void drawState() {
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    /** Puts back the state the GUI draws with. */
    private static void gameState() {
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    private static void startMesh(TextureTarget picture) {
        RenderSystem.setShaderTexture(0, picture.getColorTextureId());
        builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
    }

    private static void vertex(float x, float y, float u, float v, float alpha) {
        builder.vertex(x, y, 0.0).uv(u, v).color(alpha, alpha, alpha, alpha).endVertex();
    }

    private static void endMesh() {
        BufferUploader.drawWithShader(builder.end());
        builder = null;
    }
    //?} else if <1.21.2 {
    // 1.21.1: the targets as 1.20.1; meshes are begun on the Tesselator and built into MeshData
    /** Whether this Minecraft version can float a screen. */
    public static final boolean SUPPORTED = true;
    /** The mesh being built. */
    @Nullable
    private static BufferBuilder builder;

    /** {@code current} at the window's size, made or resized as needed, with linear filtering if asked. */
    private static TextureTarget sized(@Nullable TextureTarget current, int width, int height, boolean linear) {
        if (current == null) {
            current = new TextureTarget(width, height, true, Minecraft.ON_OSX);
            if (linear) {
                current.setFilterMode(org.lwjgl.opengl.GL11.GL_LINEAR);
            }
        } else if (current.width != width || current.height != height) {
            current.resize(width, height, Minecraft.ON_OSX);
            if (linear) {
                current.setFilterMode(org.lwjgl.opengl.GL11.GL_LINEAR);
            }
        }
        return current;
    }

    /** Clears {@code picture} to nothing and has the GUI draw into it. */
    private static void drawInto(TextureTarget picture) {
        picture.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        picture.clear(Minecraft.ON_OSX);
        picture.bindWrite(true);
    }

    /** Has the GUI draw into the game's own target again. */
    private static void backToTheGame() {
        Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
    }

    /** How the pictures are drawn: their colours are already multiplied by their alpha; no depth, both faces. */
    private static void drawState() {
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    /** Puts back the state the GUI draws with. */
    private static void gameState() {
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    private static void startMesh(TextureTarget picture) {
        RenderSystem.setShaderTexture(0, picture.getColorTextureId());
        builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
    }

    private static void vertex(float x, float y, float u, float v, float alpha) {
        builder.addVertex(x, y, 0.0f).setUv(u, v).setColor(alpha, alpha, alpha, alpha);
    }

    private static void endMesh() {
        MeshData mesh = builder.build();
        builder = null;
        // An empty mesh builds to nothing
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }
    }
    //?} else {
    // 1.21.2 to 1.21.5: every batch the GUI draws goes to the target Minecraft.getMainRenderTarget()
    // answers at that moment (1.21.2 to 1.21.4: the main target's render state binds it for each
    // batch; 1.21.5: each batch is a render pass on it), so a target bound once is left at the next
    // batch. While the screen draws into its picture the game is told the picture is its main target
    // instead (drawingInto, read by ImmersiveMinecraftMixin). 1.21.5 has no bound target or GL state
    // left: the picture is cleared through the GPU device and drawn by a render pass of its own.
    /** Whether this Minecraft version can float a screen. */
    public static final boolean SUPPORTED = true;
    /** The mesh being built, and the picture it is drawn with. */
    @Nullable
    private static BufferBuilder builder;
    @Nullable
    private static TextureTarget meshPicture;
    /** The picture the screen draws into now, or null while the game draws into its own target. */
    @Nullable
    private static RenderTarget drawingInto;

    /**
     * The picture the screen draws into now, which {@code Minecraft.getMainRenderTarget()} answers
     * in place of the game's own target while it does, or null.
     */
    @Nullable
    public static RenderTarget drawingInto() {
        return drawingInto;
    }

    /**
     * Called as a frame starts, before the world is drawn. A screen's draw ends with {@link #end};
     * should it not have ended (another mod cut the screen's draw short), the game must not go on
     * drawing the world, and showing the frame, into the picture: nothing else would give the game
     * its own target back, as the world's draw does before 1.21.2.
     */
    public static void frameStarts() {
        if (drawingInto == null) return;
        capturing = false;
        backToTheGame();
    }

    /** {@code current} at the window's size, made or resized as needed, with linear filtering if asked. */
    private static TextureTarget sized(@Nullable TextureTarget current, int width, int height, boolean linear) {
        if (current != null && current.width == width && current.height == height) {
            return current;
        }
        if (current == null) {
            //? if <1.21.5 {
            current = new TextureTarget(width, height, true);
            //?} else {
            current = new TextureTarget("Quick Skin immersive screen", width, height, true);
            //?}
        } else {
            current.resize(width, height);
        }
        if (linear) {
            //? if <1.21.5 {
            current.setFilterMode(org.lwjgl.opengl.GL11.GL_LINEAR);
            //?} else {
            current.setFilterMode(FilterMode.LINEAR);
            //?}
        }
        return current;
    }

    /** Clears {@code picture} to nothing and has the GUI draw into it. */
    private static void drawInto(TextureTarget picture) {
        //? if <1.21.5 {
        picture.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        picture.clear();
        picture.bindWrite(true);
        //?} else {
        RenderSystem.getDevice().createCommandEncoder()
                .clearColorAndDepthTextures(picture.getColorTexture(), 0, picture.getDepthTexture(), 1.0);
        //?}
        drawingInto = picture;
    }

    /** Has the GUI draw into the game's own target again. */
    private static void backToTheGame() {
        drawingInto = null;
        //? if <1.21.5 {
        Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
        //?}
    }

    /** How the pictures are drawn: their colours are already multiplied by their alpha; no depth, both faces. */
    private static void drawState() {
        //? if <1.21.5 {
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(CoreShaders.POSITION_TEX_COLOR);
        //?}
        // 1.21.5's pipeline below sets the rest
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    /** Puts back the state the GUI draws with. */
    private static void gameState() {
        //? if <1.21.5 {
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        //?}
    }

    private static void startMesh(TextureTarget picture) {
        //? if <1.21.5 {
        RenderSystem.setShaderTexture(0, picture.getColorTextureId());
        //?}
        meshPicture = picture;
        builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
    }

    private static void vertex(float x, float y, float u, float v, float alpha) {
        builder.addVertex(x, y, 0.0f).setUv(u, v).setColor(alpha, alpha, alpha, alpha);
    }

    private static void endMesh() {
        MeshData mesh = builder.build();
        TextureTarget picture = meshPicture;
        builder = null;
        meshPicture = null;
        // An empty mesh builds to nothing
        if (mesh == null || picture == null) {
            return;
        }
        //? if <1.21.5 {
        BufferUploader.drawWithShader(mesh);
        //?} else {
        // As the game's own render types draw a mesh, onto the game's own target
        try (mesh) {
            GpuBuffer vertices = PICTURE.getVertexFormat().uploadImmediateVertexBuffer(mesh.vertexBuffer());
            RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(mesh.drawState().mode());
            GpuBuffer indexBuffer = indices.getBuffer(mesh.drawState().indexCount());
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                    .createRenderPass(Minecraft.getInstance().getMainRenderTarget().getColorTexture(), OptionalInt.empty())) {
                pass.setPipeline(PICTURE);
                pass.setVertexBuffer(0, vertices);
                pass.bindSampler("Sampler0", picture.getColorTexture());
                pass.setIndexBuffer(indexBuffer, indices.type());
                pass.drawIndexed(0, mesh.drawState().indexCount());
            }
        }
        //?}
    }
    //? if >=1.21.5 {

    /**
     * How 1.21.5 draws a picture: the GUI's textured shader, its colours already multiplied by
     * their alpha, no depth, both faces. The game's own GUI pipelines share a snippet it keeps
     * private, so the same shader, uniforms, sampler and format are named here.
     */
    private static final RenderPipeline PICTURE = RenderPipeline.builder()
            .withLocation(ResourceLocation.fromNamespaceAndPath("quickskin", "pipeline/immersive_screen"))
            .withUniform("ModelViewMat", UniformType.MATRIX4X4)
            .withUniform("ProjMat", UniformType.MATRIX4X4)
            .withUniform("ColorModulator", UniformType.VEC4)
            .withVertexShader("core/position_tex_color")
            .withFragmentShader("core/position_tex_color")
            .withSampler("Sampler0")
            .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
            .withBlend(new BlendFunction(SourceFactor.ONE, DestFactor.ONE_MINUS_SRC_ALPHA))
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .build();
    //?}
    //?}
//?} else {
    /**
     * Runs {@code guiPass}, the game's own GUI pass, with the floating screen's picture as the
     * target it draws into: called by the screen hook right after the screen extracted itself, so
     * the pass draws the screen alone (the HUD is hidden) and empties the frame's GUI state, into
     * which the mesh is then handed ({@link #draw}). The hook owns the target swap, since it holds
     * the accessors; this sizes and clears the picture round it.
     */
    public static void drawScreenPass(Runnable guiPass) {
        if (!SUPPORTED) return;
        preparePicture();
        guiPass.run();
        captured = true;
    }

    /** The game's GUI pass into the picture that the screen being drawn hands {@link #begin(Runnable)}. */
    @Nullable
    private static Runnable screenPass;

    /**
     * From here on the screen is drawn into its own picture, by {@code guiPass} once it is
     * extracted ({@link #drawScreenPass}), or earlier by a dialog over a floating screen
     * ({@link #parentDrawn}).
     */
    public static void begin(Runnable guiPass) {
        screenPass = guiPass;
        parentApart = false;
        begin();
    }

    /**
     * A dialog has extracted the floating screen it opened from as its background: that alone is
     * drawn into the picture now, the mesh goes into the emptied GUI, and the dialog, with its
     * tooltip, is extracted flat over it in a stratum of its own. The screen behind keeps its
     * panels where they swing, and the dialog is never cut where they meet.
     */
    //? if <26.1 {
    public static void parentDrawn(GuiGraphics graphics) {
    //?} else {
    public static void parentDrawn(GuiGraphicsExtractor graphics) {
    //?}
        Runnable pass = screenPass;
        if (!capturing || pass == null) return;
        drawScreenPass(pass);
        capturing = false;
        parentApart = true;
        ImmersiveMode.drawOverlay();
        graphics.nextStratum();
    }

    /** Hands the frame's GUI the mesh of the last picture where {@code frame} stands it. */
    public static void draw(ImmersiveFrame frame, float alpha, float guiWidth, float guiHeight,
                            float aboveX, float aboveY) {
        if (!SUPPORTED || !captured || alpha <= 0.0f) return;
        submitMesh(ImmersiveMesh.sheet(frame, guiWidth, guiHeight), alpha);
    }

    //? if <1.21.9 {
    // 1.21.6 to 1.21.8: TextureTarget(name, width, height, depth) with its filter set on the target,
    // buildVertices(VertexConsumer, float z), GuiRenderState.submitGuiElement
    public static final boolean SUPPORTED = true;
    @Nullable
    private static TextureTarget target;
    /** The GUI state the frame's screens extract into, which the mesh is handed to. */
    @Nullable
    private static GuiRenderState frameState;
    /** Counts the frames, so the mesh goes into a frame's GUI once. */
    private static int frameNumber;
    private static int submittedFrame = -1;

    /**
     * A frame starts: {@code state} is the GUI state its screens extract into, which the game
     * draws once at the end of the frame and the floating screen's mesh is handed to.
     */
    public static void frameStarts(GuiRenderState state) {
        frameState = state;
        frameNumber++;
    }

    /**
     * From here on the screen extracts itself for its picture: what it holds back to draw above
     * itself ({@code ImmersiveMode.above}) waits until the picture is drawn.
     */
    private static void begin() {
        capturing = true;
    }

    /** The screen and what it drew above itself are extracted. */
    public static void end() {
        capturing = false;
    }

    /** The picture the screen's own GUI pass draws into ({@link #drawScreenPass}), null before the first. */
    @Nullable
    public static RenderTarget picture() {
        return target;
    }

    /** Makes the picture the window's size, if it is not, and clears it to nothing. */
    private static void preparePicture() {
        Window window = Minecraft.getInstance().getWindow();
        int width = Math.max(1, window.getWidth());
        int height = Math.max(1, window.getHeight());
        if (target == null) {
            target = new TextureTarget("Quick Skin immersive screen", width, height, true);
            target.setFilterMode(FilterMode.LINEAR);
        } else if (target.width != width || target.height != height) {
            target.resize(width, height);
            target.setFilterMode(FilterMode.LINEAR);
        }
        RenderSystem.getDevice().createCommandEncoder()
                .clearColorAndDepthTextures(target.getColorTexture(), 0, target.getDepthTexture(), 1.0);
    }

    /**
     * Hands the frame's GUI state the mesh, once a frame: the screen's hook hands it right after
     * the picture is drawn, below the tooltip, and the hook before the toasts again while the view
     * closes, when no screen is drawn.
     */
    private static void submitMesh(float[] vertices, float alpha) {
        GuiRenderState state = frameState;
        TextureTarget picture = target;
        if (state == null || picture == null || vertices.length == 0 || submittedFrame == frameNumber) return;
        submittedFrame = frameNumber;
        int shade = Math.round(Math.max(0.0f, Math.min(1.0f, alpha)) * 255.0f);
        // The picture's colours are already multiplied by its alpha, and so is the tint
        int colour = shade << 24 | shade << 16 | shade << 8 | shade;
        Window window = Minecraft.getInstance().getWindow();
        state.submitGuiElement(new Mesh(vertices, colour, TextureSetup.singleTexture(picture.getColorTextureView()),
                new ScreenRectangle(0, 0, window.getGuiScaledWidth(), window.getGuiScaledHeight())));
    }

    /** The floating picture as one GUI element: the quads of {@link ImmersiveMesh}, in its order. */
    private record Mesh(float[] vertices, int colour, TextureSetup textureSetup, ScreenRectangle bounds)
            implements GuiElementRenderState {
        @Override
        public void buildVertices(VertexConsumer consumer, float z) {
            for (int i = 0; i < vertices.length; i += ImmersiveMesh.STRIDE) {
                consumer.addVertex(vertices[i], vertices[i + 1], z).setUv(vertices[i + 2], vertices[i + 3])
                        .setColor(colour);
            }
        }

        @Override
        public RenderPipeline pipeline() {
            return RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA;
        }

        @Override
        @Nullable
        public ScreenRectangle scissorArea() {
            return null;
        }
    }
    //?} else if <26.1 {
    // 1.21.9 to 1.21.11: buildVertices(VertexConsumer); singleTexture(view, sampler) from 1.21.11
    /** Whether this Minecraft version can float a screen. */
    public static final boolean SUPPORTED = true;
    /** The picture the screen's own GUI pass draws into. */
    @Nullable
    private static TextureTarget picture;
    /** The GUI the game extracts this frame, which the mesh goes into, as the frame's start told it. */
    @Nullable
    private static GuiRenderState frameGui;
    /** Counts the frames, so the mesh goes into a frame's GUI once: the screen hook or, while closing, the toasts hook. */
    private static int frames;
    private static int submittedIn = -1;

    /** A frame starts, whose GUI is extracted into {@code gui}. */
    public static void newFrame(GuiRenderState gui) {
        frameGui = gui;
        frames++;
    }

    /**
     * The screen starts to extract itself into the floating picture: what it draws above itself
     * ({@link ImmersiveMode#above}) waits until the picture is drawn.
     */
    private static void begin() {
        capturing = true;
    }

    /** The screen is in its picture: what it draws from here on is not held back. */
    public static void end() {
        capturing = false;
    }

    /** The target the screen's own GUI pass draws into, as {@link #drawScreenPass} sized and cleared it. */
    @Nullable
    public static RenderTarget picture() {
        return picture;
    }

    private static void preparePicture() {
        Minecraft minecraft = Minecraft.getInstance();
        int width = Math.max(1, minecraft.getWindow().getWidth());
        int height = Math.max(1, minecraft.getWindow().getHeight());
        if (picture == null) {
            picture = new TextureTarget("Quick Skin immersive screen", width, height, true);
            linear(picture);
        } else if (picture.width != width || picture.height != height) {
            picture.resize(width, height);
            linear(picture);
        }
        RenderSystem.getDevice().createCommandEncoder()
                .clearColorAndDepthTextures(picture.getColorTexture(), 0, picture.getDepthTexture(), 1.0);
    }

    /** The picture is sampled smoothly where the mesh stretches it (from 1.21.11 the sampler says so instead). */
    private static void linear(TextureTarget target) {
        //? if <1.21.11 {
        target.setFilterMode(FilterMode.LINEAR);
        //?}
    }

    private static void submitMesh(float[] vertices, float alpha) {
        GuiRenderState gui = frameGui;
        TextureTarget shown = picture;
        if (gui == null || shown == null || submittedIn == frames) return;
        submittedIn = frames;
        int a = Math.round(Math.max(0.0f, Math.min(1.0f, alpha)) * 255.0f);
        //? if <1.21.11 {
        TextureSetup texture = TextureSetup.singleTexture(shown.getColorTextureView());
        //?} else {
        TextureSetup texture = TextureSetup.singleTexture(shown.getColorTextureView(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        //?}
        Minecraft minecraft = Minecraft.getInstance();
        ScreenRectangle bounds = new ScreenRectangle(0, 0, minecraft.getWindow().getGuiScaledWidth(),
                minecraft.getWindow().getGuiScaledHeight());
        // The picture's colours are already multiplied by its alpha, so is the tint
        gui.submitGuiElement(new Mesh(vertices, (a << 24) | (a << 16) | (a << 8) | a, texture, bounds));
    }

    /** The floating picture as one element of the frame's GUI: every cell of the mesh is a quad of it. */
    private record Mesh(float[] vertices, int colour, TextureSetup textureSetup, ScreenRectangle bounds)
            implements GuiElementRenderState {
        @Override
        public void buildVertices(VertexConsumer consumer) {
            for (int i = 0; i < vertices.length; i += ImmersiveMesh.STRIDE) {
                consumer.addVertex(vertices[i], vertices[i + 1], 0.0f)
                        .setUv(vertices[i + 2], vertices[i + 3]).setColor(colour);
            }
        }

        @Override
        public RenderPipeline pipeline() {
            return RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA;
        }

        @Override
        @Nullable
        public ScreenRectangle scissorArea() {
            return null;
        }
    }
    //?} else {
    // 26.1 to 26.3: the whole frame is extracted (GameRenderer.extract) before anything of it is
    // drawn (GameRenderer.render), so the screen's own pass runs during the extraction, into the
    // picture only: the game's own target is cleared when the frame is drawn. The mesh goes into
    // the frame's GUI state as one element, after the screen's pass emptied it.
    public static final boolean SUPPORTED = true;
    @Nullable
    private static TextureTarget picture;
    /** Whether the mesh went into this frame's GUI already: the screen's hook and the toasts' both hand it. */
    private static boolean submitted;

    /**
     * A frame's GUI is being extracted afresh ({@code GameRenderer.extractGui} to 26.1.2,
     * {@code Gui.extractRenderState} after), every frame, view or not. Once the view has ended, the
     * picture (a window-sized colour and depth texture) is let go: the last frame that drew it was
     * drawn before this one is extracted, and the next view makes a new one.
     */
    public static void newFrame() {
        submitted = false;
        if (picture != null && !ImmersiveMode.running()) {
            picture.destroyBuffers();
            picture = null;
        }
    }

    /** From here on the screen is drawn into its own picture, and what it draws above itself is held back. */
    private static void begin() {
        capturing = true;
    }

    /** The screen is extracted: what it draws above itself is drawn at once again. */
    public static void end() {
        capturing = false;
    }

    /** The target the screen's pass draws into, sized and cleared by {@link #drawScreenPass}; null before. */
    @Nullable
    public static RenderTarget picture() {
        return picture;
    }

    private static void preparePicture() {
        Window window = Minecraft.getInstance().getWindow();
        int width = Math.max(1, window.getWidth());
        int height = Math.max(1, window.getHeight());
        if (picture == null) {
        //? if <26.2 {
            picture = new TextureTarget("Quick Skin immersive screen", width, height, true);
        //?} else if <26.3 {
            picture = new TextureTarget("Quick Skin immersive screen", width, height, true, GpuFormat.RGBA8_UNORM);
        //?} else {
            picture = new TextureTarget("Quick Skin immersive screen", width, height, GpuFormat.RGBA8_UNORM,
                    GpuFormat.D32_FLOAT);
        //?}
        } else if (picture.width != width || picture.height != height) {
            picture.resize(width, height);
        }
        // Clear to nothing, and the depth to "far" as the game clears its own before its GUI pass
        // (1 to 26.1.2, 0 from 26.2, which draws with reverse Z)
        //? if <26.2 {
        RenderSystem.getDevice().createCommandEncoder()
                .clearColorAndDepthTextures(picture.getColorTexture(), 0, picture.getDepthTexture(), 1.0);
        //?} else {
        RenderSystem.getDevice().createCommandEncoder()
                .clearColorAndDepthTextures(picture.getColorTexture(), new Vector4f(0.0f), picture.getDepthTexture(), 0.0);
        //?}
    }

    private static void submitMesh(float[] vertices, float alpha) {
        TextureTarget source = picture;
        if (submitted || source == null || source.getColorTextureView() == null) return;
        submitted = true;
        Minecraft minecraft = Minecraft.getInstance();
        int a = Math.round(Math.max(0.0f, Math.min(1.0f, alpha)) * 255.0f);
        // The picture's colours are already multiplied by its alpha, and so is the tint
        int color = (a << 24) | (a << 16) | (a << 8) | a;
        TextureSetup texture = TextureSetup.singleTexture(source.getColorTextureView(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        ScreenRectangle bounds = new ScreenRectangle(0, 0, minecraft.getWindow().getGuiScaledWidth(),
                minecraft.getWindow().getGuiScaledHeight());
        //? if <26.2 {
        GuiRenderState state = minecraft.gameRenderer.getGameRenderState().guiRenderState;
        //?} else {
        GuiRenderState state = minecraft.gameRenderer.gameRenderState().guiRenderState;
        //?}
        state.addGuiElement(new Mesh(vertices, color, texture, bounds));
    }

    /** The floating picture as one GUI element of many quads ({@link ImmersiveMesh}). */
    private record Mesh(float[] vertices, int color, TextureSetup textureSetup, ScreenRectangle bounds)
            implements GuiElementRenderState {
        @Override
        public void buildVertices(VertexConsumer consumer) {
            for (int i = 0; i < vertices.length; i += ImmersiveMesh.STRIDE) {
                consumer.addVertex(vertices[i], vertices[i + 1], 0.0f)
                        .setUv(vertices[i + 2], vertices[i + 3]).setColor(color);
            }
        }

        @Override
        public RenderPipeline pipeline() {
            return RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA;
        }

        @Override
        @Nullable
        public ScreenRectangle scissorArea() {
            return null;
        }
    }
    //?}
//?}
}
