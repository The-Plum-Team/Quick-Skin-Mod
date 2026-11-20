package com.quickskin.mod.forge;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.immersive.ImmersiveOpening;
import com.quickskin.mod.client.immersive.LegacyOpeningRenderer;
import com.quickskin.mod.platform.QuickSkinInfo;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.io.IOException;

/** Opens a round window through foreground blocks in the Forge 1.20.1 immersive view. */
@Mod.EventBusSubscriber(modid = QuickSkinInfo.MOD_ID, value = Dist.CLIENT)
public final class ImmersiveBlockReveal {
    private static final RandomSource modelRandom = RandomSource.create();
    private static final long BREATH_NANOS = 6_000_000_000L;
    private static ShaderInstance shader;
    private static final PoseStack worldView = new PoseStack();
    private static ImmersiveOpening pending;
    private static TextureTarget terrain;
    private static boolean drawingOpening;
    private static TextureTarget original;
    private static DynamicTexture blocks;

    @Mod.EventBusSubscriber(modid = QuickSkinInfo.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Shaders {
        @SubscribeEvent
        public static void register(RegisterShadersEvent event) throws IOException {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    new ResourceLocation(QuickSkinInfo.MOD_ID, "immersive_block_reveal"),
                    DefaultVertexFormat.POSITION), loaded -> {
                        shader = loaded;
                        ImmersiveMode.enableBlockReveal();
                    });
        }
    }

    @SubscribeEvent
    public static void releaseAfterMenu(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.END && !ImmersiveMode.running() && blocks != null) {
            blocks.close();
            blocks = null;
            if (original != null) original.destroyBuffers();
            if (terrain != null) terrain.destroyBuffers();
            original = null;
            terrain = null;
            pending = null;
        }
    }

    @SubscribeEvent
    public static void reveal(RenderLevelStageEvent event) {
        // Forge also fires chunk-layer events while Oculus renders its light-space shadow map.
        if (drawingOpening || !ImmersiveMode.posesPlayer() || OculusShadowPass.active()) return;
        Minecraft game = Minecraft.getInstance();
        LocalPlayer player = game.player;
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) pending = null;
        if (player == null || shader == null
                || !event.getCamera().isDetached() || event.getCamera().getEntity() != player) return;
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) {
            ImmersiveTerrainInterior.draw(event);
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS) {
            worldView.last().pose().set(event.getPoseStack().last().pose());
            worldView.last().normal().set(event.getPoseStack().last().normal());
            pending = findOpening(event, player, game.getMainRenderTarget());
            if (pending != null) {
                RenderTarget world = game.getMainRenderTarget();
                captureTerrainDepth(world, pending);
            }
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL && pending != null) {
            ImmersiveOpening opening = pending;
            pending = null;
            renderOpening(event, player, game.getMainRenderTarget(), opening);
        }
    }

    private static ImmersiveOpening findOpening(RenderLevelStageEvent event, LocalPlayer player, RenderTarget world) {
        Minecraft game = Minecraft.getInstance();
        ClientLevel level = game.level;
        if (level == null) return null;
        float time = event.getPartialTick();
        Vec3 feet = new Vec3(Mth.lerp(time, player.xOld, player.getX()), Mth.lerp(time, player.yOld, player.getY()),
                Mth.lerp(time, player.zOld, player.getZ()));
        Matrix4f view = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(worldView.last().pose());
        var pos = new BlockPos.MutableBlockPos();
        ImmersiveOpening opening = ImmersiveOpening.find(event.getProjectionMatrix(), view, world.width, world.height,
                event.getCamera().getPosition(), feet, player.getBbHeight(), player.getBbWidth(), false,
                (x, y, z, cell) -> {
                    pos.set(x, y, z);
                    var state = level.getBlockState(pos);
                    if (state.getRenderShape() == RenderShape.INVISIBLE && !state.hasBlockEntity()) return false;
                    cell.set(state.getOffset(level, pos), state.getCollisionShape(level, pos).isEmpty(),
                            drawnAfterTerrain(level, state, pos));
                    return true;
                });
        if (opening != null) uploadCells(opening.cells());
        return opening;
    }

    private static void uploadCells(byte[] grid) {
        int side = ImmersiveOpening.SIDE;
        if (blocks == null) blocks = new DynamicTexture(side * side, side, true);
        var pixels = blocks.getPixels();
        for (int i = 0; i < grid.length; i++) {
            int red = (grid[i] & ImmersiveOpening.OCCUPIED) == 0 ? 0 : 255;
            int green = (grid[i] & ImmersiveOpening.LATE) == 0 ? 0 : 255;
            pixels.setPixelRGBA(i % (side * side), i / (side * side), red == 0 ? 0 : 0xff000000 | (green << 8) | red);
        }
        blocks.upload();
    }

    private static void renderOpening(RenderLevelStageEvent event, LocalPlayer player, RenderTarget world,
                                      ImmersiveOpening opening) {
        ShaderInstance previous = RenderSystem.getShader();
        int[] textures = new int[4];
        for (int i = 0; i < textures.length; i++) textures[i] = RenderSystem.getShaderTexture(i);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        int dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        int rgbEquation = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        int alphaEquation = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        boolean saved = false;
        boolean composed = false;
        try {
            RenderSystem.depthMask(true);
            blit(world, original, GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, null);
            saved = true;
            world.bindWrite(true);
            drawClippedWorld(event, player, opening);
            composite(world, opening);
            composed = true;
        } finally {
            // Later world overlays and the menu must get the normal camera's depth back.
            if (saved) blit(original, world, GL11.GL_DEPTH_BUFFER_BIT | (composed ? 0 : GL11.GL_COLOR_BUFFER_BIT), null);
            world.bindWrite(true);
            for (int i = 0; i < textures.length; i++) RenderSystem.setShaderTexture(i, textures[i]);
            RenderSystem.depthMask(depthWrite);
            if (depthTest) RenderSystem.enableDepthTest();
            else RenderSystem.disableDepthTest();
            RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            GL20.glBlendEquationSeparate(rgbEquation, alphaEquation);
            if (blend) RenderSystem.enableBlend();
            else RenderSystem.disableBlend();
            RenderSystem.setShader(() -> previous);
        }
    }

    private static void drawClippedWorld(RenderLevelStageEvent event, LocalPlayer player, ImmersiveOpening opening) {
        Minecraft game = Minecraft.getInstance();
        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var sorting = RenderSystem.getVertexSorting();
        float cut = opening.cut();
        // This remaps the original depth interval [cut, 1] to [0, 1] without moving a pixel in XY.
        Matrix4f clipped = new Matrix4f().m22(1 / (1 - cut)).m32(-cut / (1 - cut)).mul(event.getProjectionMatrix());
        int[] priorScissor = null;
        int left = opening.left(), bottom = opening.bottom();
        int right = left + opening.width(), top = bottom + opening.height();
        if (GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)) {
            priorScissor = new int[4];
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, priorScissor);
            left = Math.max(left, priorScissor[0]);
            bottom = Math.max(bottom, priorScissor[1]);
            right = Math.min(right, priorScissor[0] + priorScissor[2]);
            top = Math.min(top, priorScissor[1] + priorScissor[3]);
        }
        RenderSystem.setProjectionMatrix(clipped, sorting);
        RenderSystem.enableScissor(left, bottom, Math.max(0, right - left), Math.max(0, top - bottom));
        drawingOpening = true;
        try {
            LegacyOpeningRenderer.clearOpeningDepth(opening);
            // Shader packs derive frame deltas from the engine clock supplied to renderLevel.
            Runnable drawWorld = () -> game.levelRenderer.renderLevel(worldView, event.getPartialTick(),
                    Util.getNanos(), false, event.getCamera(), game.gameRenderer, game.gameRenderer.lightTexture(), clipped);
            ImmersiveUnclippedPlayer.render(player, event.getCamera(), worldView, event.getPartialTick(),
                    previousProjection, opening, drawWorld);
        } finally {
            drawingOpening = false;
            if (priorScissor == null) RenderSystem.disableScissor();
            else RenderSystem.enableScissor(priorScissor[0], priorScissor[1], priorScissor[2], priorScissor[3]);
            RenderSystem.setProjectionMatrix(previousProjection, sorting);
        }
    }

    private static void composite(RenderTarget world, ImmersiveOpening opening) {
        world.bindWrite(true);
        shader.safeGetUniform("FromClip").set(opening.fromClip());
        Vec3 camera = opening.cameraInGrid();
        shader.safeGetUniform("CameraInGrid").set((float)camera.x, (float)camera.y, (float)camera.z);
        shader.safeGetUniform("FloorY").set(opening.floor());
        // Real time also advances while a single-player menu pauses world ticks.
        double phase = (System.nanoTime() % BREATH_NANOS) * (2 * Math.PI / BREATH_NANOS);
        shader.safeGetUniform("Breath").set((float) Math.sin(phase));
        shader.safeGetUniform("Opening").set(opening.x(), opening.y(), opening.radius(), opening.cut());
        RenderSystem.setShaderTexture(0, original.getColorTextureId());
        RenderSystem.setShaderTexture(1, original.getDepthTextureId());
        RenderSystem.setShaderTexture(2, blocks.getId());
        RenderSystem.setShaderTexture(3, terrain.getDepthTextureId());
        RenderSystem.setShader(() -> shader);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.blendEquation(GL14.GL_FUNC_ADD);
        RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
        BufferBuilder quad = Tesselator.getInstance().getBuilder();
        quad.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        quad.vertex(-1, -1, 0).endVertex();
        quad.vertex(1, -1, 0).endVertex();
        quad.vertex(1, 1, 0).endVertex();
        quad.vertex(-1, 1, 0).endVertex();
        BufferUploader.drawWithShader(quad.end());
    }

    private static boolean drawnAfterTerrain(ClientLevel level, BlockState state, BlockPos pos) {
        if (state.hasBlockEntity()) return true;
        var model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        ModelData data = level.getModelDataManager().getAt(pos);
        data = model.getModelData(level, pos, state, data == null ? ModelData.EMPTY : data);
        modelRandom.setSeed(state.getSeed(pos));
        var layers = model.getRenderTypes(state, modelRandom, data);
        return layers.contains(RenderType.translucent()) || layers.contains(RenderType.tripwire());
    }

    private static void captureTerrainDepth(RenderTarget world, ImmersiveOpening opening) {
        // Oculus can be writing to its own G-buffer here. Its terrain shares the world's
        // depth texture. Leave the shader pack's draw target and viewport in place afterwards.
        int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        try {
            prepareTargets(world);
            blit(world, terrain, GL11.GL_DEPTH_BUFFER_BIT, opening);
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        }
    }

    private static void prepareTargets(RenderTarget world) {
        if (original == null) {
            original = new TextureTarget(world.width, world.height, true, Minecraft.ON_OSX);
            terrain = new TextureTarget(world.width, world.height, true, Minecraft.ON_OSX);
        } else if (original.width != world.width || original.height != world.height) {
            original.resize(world.width, world.height, Minecraft.ON_OSX);
            terrain.resize(world.width, world.height, Minecraft.ON_OSX);
        }
        if (world.isStencilEnabled() && !original.isStencilEnabled()) {
            original.enableStencil();
            terrain.enableStencil();
        }
    }

    private static void blit(RenderTarget source, RenderTarget target, int mask, ImmersiveOpening area) {
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target.frameBufferId);
        int x = area == null ? 0 : area.left();
        int y = area == null ? 0 : area.bottom();
        int right = area == null ? source.width : x + area.width();
        int top = area == null ? source.height : y + area.height();
        GL30.glBlitFramebuffer(x, y, right, top, x, y, right, top, mask, GL11.GL_NEAREST);
    }
}
