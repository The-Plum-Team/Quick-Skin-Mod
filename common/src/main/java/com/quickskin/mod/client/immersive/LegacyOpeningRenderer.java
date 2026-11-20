package com.quickskin.mod.client.immersive;

//? if <1.21.6 {
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.platform.PlatformHelper;
import com.quickskin.mod.platform.QuickSkinInfo;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.io.IOException;
import java.util.function.Consumer;

//? if <1.21 {
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.world.entity.Entity;
import java.util.function.BiFunction;
//?} else {
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
//?}
//? if >=1.21.5 {
import com.mojang.blaze3d.opengl.GlStateManager;
//?}
//? if >=1.21.2 {
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
//?}
//?}

/** Bound to the normal camera around Minecraft's terrain and entity passes on legacy renderers. */
public final class LegacyOpeningRenderer {
    //? if <1.21.6 {
    private static final float[] FAR_DEPTH = {1.0f};
    private static LegacyOpeningTarget target;
    private static boolean failed, drawing, captured;
    private static ImmersiveOpening opening;
    private static Camera camera;
    private static float partial;
    private static Matrix4f view, projection;
    private static Consumer<Matrix4f> redraw;
    private static MultiBufferSource.BufferSource shadow;
    private static final Matrix4f shadowView = new Matrix4f();
    private static boolean shadowQueued;

    public static boolean available() {
        // The Forge 1.20.1 adapter uses Forge's baked model layers and render events.
        //? if <1.21 {
        if (PlatformHelper.isModLoaded("forge")) return false;
        //?}
        return !failed;
    }

    public static boolean beforeWorld() {
        if (drawing || !available() || ImmersiveShaderPacks.shadows()) return false;
        opening = null; redraw = null; captured = false;
        if (!ImmersiveMode.running()) {
            releaseAfterMenu();
            return false;
        }
        if (target == null) {
            try {
                target = LegacyOpeningTarget.create();
                ImmersiveMode.enableBlockReveal();
            } catch (IOException | RuntimeException failure) {
                QuickSkinInfo.LOGGER.warn("Could not load the immersive opening shader", failure);
                failed = true;
                return false;
            }
        }
        return ImmersiveMode.posesPlayer();
    }

    public static void releaseAfterMenu() {
        if (ImmersiveMode.running()) return;
        LegacyDepthRange.release();
        if (target != null) {
            target.close();
            target = null;
            redraw = null;
            opening = null;
        }
    }

    public static void begin(Camera eye, float time, Matrix4f cameraView, Matrix4f cameraProjection,
                             Consumer<Matrix4f> clippedWorld) {
        Minecraft game = Minecraft.getInstance();
        LocalPlayer player = game.player;
        if (player == null || game.level == null || eye.getEntity() != player || !eye.isDetached()) return;
        camera = eye; partial = time; view = new Matrix4f(cameraView); projection = new Matrix4f(cameraProjection);
        Vec3 feet = new Vec3(Mth.lerp(time, player.xOld, player.getX()), Mth.lerp(time, player.yOld, player.getY()),
                Mth.lerp(time, player.zOld, player.getZ()));
        var pos = new BlockPos.MutableBlockPos();
        var level = game.level;
        var output = game.getMainRenderTarget();
        opening = ImmersiveOpening.find(projection, view, output.width, output.height, eye.getPosition(), feet,
                player.getBbHeight(), player.getBbWidth(), false, (x, y, z, cell) -> {
                    pos.set(x, y, z);
                    var state = level.getBlockState(pos);
                    if (state.getRenderShape() == RenderShape.INVISIBLE) return false;
                    RenderType layer = ItemBlockRenderTypes.getChunkRenderType(state);
                    cell.set(LegacyTerrainGeometry.offset(state, level, pos), state.getCollisionShape(level, pos).isEmpty(),
                            state.hasBlockEntity() || layer == RenderType.translucent() || layer == RenderType.tripwire());
                    return true;
                });
        redraw = clippedWorld;
    }

    /** Vertex buffers see the engine's model-view as well as their pose stack. */
    private static PoseStack modelView() {
        Matrix4f local = new Matrix4f(RenderSystem.getModelViewMatrix()).invert().mul(view);
        PoseStack poses = new PoseStack();
        poses.last().pose().set(local);
        poses.last().normal().set(local).invert().transpose();
        return poses;
    }

    public static void beforeLayer(RenderType layer) {
        if (redraw == null || drawing || ImmersiveShaderPacks.shadows()) return;
        if (layer == RenderType.cutoutMipped()) {
            LegacyTerrainGeometry.interior(camera.getPosition(), modelView(), projection, view, partial);
        }
    }

    public static void beforeEntities() {
        if (opening == null || captured || drawing || ImmersiveShaderPacks.shadows()) return;
        target.terrain(Minecraft.getInstance().getMainRenderTarget(), opening);
        captured = true;
    }

    public static void finish() {
        if (drawing || redraw == null || ImmersiveShaderPacks.shadows()) return;
        try {
            if (opening == null) return;
            var game = Minecraft.getInstance();
            LocalPlayer player = game.player;
            if (player == null) return;
            var world = game.getMainRenderTarget();
            Matrix4f normal = new Matrix4f(RenderSystem.getProjectionMatrix());
            //? if <1.21.2 {
            var sorting = RenderSystem.getVertexSorting();
            //?} else {
            var sorting = RenderSystem.getProjectionType();
            //?}
            boolean pack = ImmersiveShaderPacks.active();
            double[] range = new double[2];
            GL11.glGetDoublev(GL11.GL_DEPTH_RANGE, range);
            float cut = opening.cut();
            Matrix4f cropped = new Matrix4f().m22(1 / (1 - cut)).m32(-cut / (1 - cut)).mul(projection);
            target.snapshot(world);
            boolean complete = false;
            boolean originalDepthWrites = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            int[] priorScissor = restrictToOpening(opening);
            drawing = true;
            shadowQueued = false;
            try {
                try {
                    target.selectWorld(world);
                    depthWrites(true);
                    clearOpeningDepth(opening);
                    RenderSystem.setProjectionMatrix(cropped, sorting);
                    // A shader pack reconstructs terrain positions using the cropped projection.
                    // Its post-processes need ordinary [near,far] depths until the pack finishes.
                    if (!pack) GL11.glDepthRange(Mth.lerp(cut, range[0], range[1]), range[1]);
                    redraw.accept(cropped);
                    game.renderBuffers().bufferSource().endBatch();
                } finally {
                    GL11.glDepthRange(range[0], range[1]);
                    RenderSystem.setProjectionMatrix(projection, sorting);
                }
                target.selectWorld(world);
                if (pack) LegacyDepthRange.restore(opening, range[1]);
                // Iris finalizes its fullscreen colour pass with depth writes disabled.
                // The skin must write depth before its cape and the other player layers draw.
                depthWrites(true);
                PoseStack poses = modelView();
                LegacyTerrainGeometry.ground(camera.getPosition(), poses, opening, partial);
                if (shadowQueued) flushShadow();
                if (LegacyTerrainGeometry.section(camera.getPosition(), poses, opening, partial)) {
                    target.drawSection(opening);
                }
                if (needsWholePlayer(opening)) drawPlayer(player, poses);
                target.composite(opening, captured);
                complete = true;
            } finally {
                drawing = false;
                depthWrites(originalDepthWrites);
                RenderSystem.setProjectionMatrix(normal, sorting);
                if (priorScissor == null) RenderSystem.disableScissor();
                else RenderSystem.enableScissor(priorScissor[0], priorScissor[1], priorScissor[2], priorScissor[3]);
                if (shadowQueued) shadow = null;
                target.restore(!complete);
            }
        } finally {
            redraw = null; opening = null;
        }
    }

    private static void depthWrites(boolean writable) {
        //? if >=1.21.5 {
        GlStateManager._depthMask(writable);
        //?} else {
        RenderSystem.depthMask(writable);
        //?}
    }

    /** Iris clears its colour attachments on a world pass but can retain the first camera's wall depth. */
    public static void clearOpeningDepth(ImmersiveOpening opening) {
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int[] box = new int[4];
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, box);
        boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        try {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(opening.left(), opening.bottom(), opening.width(), opening.height());
            GL11.glDepthMask(true);
            GL30.glClearBufferfv(GL11.GL_DEPTH, 0, FAR_DEPTH);
        } finally {
            GL11.glDepthMask(depthWrite);
            GL11.glScissor(box[0], box[1], box[2], box[3]);
            if (!scissor) GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
    }

    private static int[] restrictToOpening(ImmersiveOpening aperture) {
        int[] previous = null;
        //? if >=1.21.5 {
        var state = RenderSystem.SCISSOR_STATE;
        if (state.isEnabled()) {
            previous = new int[]{state.getX(), state.getY(), state.getWidth(), state.getHeight()};
        }
        //?} else {
        if (GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)) {
            previous = new int[4];
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, previous);
        }
        //?}
        int left = aperture.left();
        int bottom = aperture.bottom();
        int right = left + aperture.width();
        int top = bottom + aperture.height();
        if (previous != null) {
            left = Math.max(left, previous[0]);
            bottom = Math.max(bottom, previous[1]);
            right = Math.min(right, previous[0] + previous[2]);
            top = Math.min(top, previous[1] + previous[3]);
        }
        RenderSystem.enableScissor(left, bottom, Math.max(0, right - left), Math.max(0, top - bottom));
        return previous;
    }

    public static boolean needsWholePlayer(ImmersiveOpening opening) {
        // Shader packs reconstruct entity depth using their original camera, even in the cropped terrain pass.
        return opening.crossesPlayer() || ImmersiveShaderPacks.active();
    }

    public static boolean omit(Object rendered) {
        return drawing && !drawingPlayer && opening != null && local(rendered)
                && needsWholePlayer(opening) && !ImmersiveShaderPacks.shadows();
    }

    private static boolean drawingPlayer;

    private static boolean local(Object rendered) {
        var player = Minecraft.getInstance().player;
        //? if <1.21.2 {
        return player != null && rendered == player;
        //?} else {
        return player != null && rendered instanceof PlayerRenderState state && state.id == player.getId();
        //?}
    }

    //? if <1.21 {
    private static BiFunction<Entity, MultiBufferSource, MultiBufferSource> forgeShadow;

    public static void useForgeShadows(BiFunction<Entity, MultiBufferSource, MultiBufferSource> capture) {
        forgeShadow = capture;
    }
    //?}

    public static MultiBufferSource shadow(Object rendered, MultiBufferSource buffers) {
        //? if <1.21 {
        if (forgeShadow != null && rendered instanceof Entity caster) return forgeShadow.apply(caster, buffers);
        //?}
        if (!drawing || !local(rendered) || ImmersiveShaderPacks.shadows()) return buffers;
        if (shadow == null) {
            //? if <1.21 {
            shadow = MultiBufferSource.immediate(new BufferBuilder(2048));
            //?} else {
            shadow = MultiBufferSource.immediate(new ByteBufferBuilder(2048));
            //?}
        }
        shadowView.set(RenderSystem.getModelViewMatrix());
        shadowQueued = true;
        return shadow;
    }

    // Shadow vertices have already traversed the dispatcher's pose stack. They still expect
    // the global view matrix that was active when the entity layer queued them.
    private static void flushShadow() {
        var stack = RenderSystem.getModelViewStack();
        //? if <1.21 {
        stack.pushPose();
        stack.last().pose().set(shadowView);
        //?} else {
        stack.pushMatrix().set(shadowView);
        //?}
        try {
            //? if <1.21.2 {
            RenderSystem.applyModelViewMatrix();
            //?}
            shadow.endBatch();
            shadowQueued = false;
        } finally {
            //? if <1.21 {
            stack.popPose();
            //?} else {
            stack.popMatrix();
            //?}
            //? if <1.21.2 {
            RenderSystem.applyModelViewMatrix();
            //?}
        }
    }

    private static void drawPlayer(LocalPlayer player, PoseStack poses) {
        var game = Minecraft.getInstance();
        var buffers = game.renderBuffers().bufferSource();
        var dispatcher = game.getEntityRenderDispatcher();
        //? if <1.21.2 {
        var renderer = dispatcher.getRenderer(player);
        Vec3 offset = renderer.getRenderOffset(player, partial);
        //?} else {
        drawPlayerState(player, poses, dispatcher.getRenderer(player));
    }

    private static <S extends EntityRenderState> void drawPlayerState(LocalPlayer player, PoseStack poses,
                                                                      EntityRenderer<? super LocalPlayer, S> renderer) {
        var game = Minecraft.getInstance();
        var buffers = game.renderBuffers().bufferSource();
        var dispatcher = game.getEntityRenderDispatcher();
        S state = renderer.createRenderState(player, partial);
        Vec3 offset = renderer.getRenderOffset(state);
        //?}
        Vec3 eye = camera.getPosition();
        poses.pushPose();
        drawingPlayer = true;
        try {
            poses.translate(Mth.lerp(partial, player.xOld, player.getX()) - eye.x + offset.x,
                    Mth.lerp(partial, player.yOld, player.getY()) - eye.y + offset.y,
                    Mth.lerp(partial, player.zOld, player.getZ()) - eye.z + offset.z);
            int light = dispatcher.getPackedLightCoords(player, partial);
            //? if <1.21.2 {
            renderer.render(player, Mth.lerp(partial, player.yRotO, player.getYRot()), partial, poses, buffers, light);
            //?} else {
            renderer.render(state, poses, buffers, light);
            //?}
            buffers.endBatch();
        } finally {
            drawingPlayer = false;
            poses.popPose();
        }
    }
    //?}
    private LegacyOpeningRenderer() {}
}
