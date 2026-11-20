package com.quickskin.mod.forge;

import com.mojang.blaze3d.systems.RenderSystem;
import com.quickskin.mod.client.immersive.ImmersiveOpening;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.platform.QuickSkinInfo;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/** Keeps the local player's world model intact when the opening cuts into their block. */
@Mod.EventBusSubscriber(modid = QuickSkinInfo.MOD_ID, value = Dist.CLIENT)
public final class ImmersiveUnclippedPlayer {
    private static LocalPlayer clippedPlayer;
    private static boolean omitModel;
    private static MultiBufferSource.BufferSource deferredShadow;

    private ImmersiveUnclippedPlayer() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void omitClippedModel(RenderPlayerEvent.Pre event) {
        if (omitModel && event.getEntity() == clippedPlayer) event.setCanceled(true);
    }

    /** The dispatcher still chooses the receivers, opacity and texture of the local player's shadow. */
    public static MultiBufferSource shadowBuffers(Entity caster, MultiBufferSource worldBuffers) {
        if (caster != clippedPlayer) return worldBuffers;
        if (deferredShadow == null) deferredShadow = MultiBufferSource.immediate(new BufferBuilder(1024));
        return deferredShadow;
    }

    /** Called with the opening's scissor and cropped projection already active. */
    static void render(LocalPlayer player, Camera camera, PoseStack worldView, float partialTick,
                       Matrix4f normalProjection, ImmersiveOpening opening, Runnable drawClippedWorld) {
        var clippedProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var sorting = RenderSystem.getVertexSorting();
        double[] range = new double[2];
        GL11.glGetDoublev(GL11.GL_DEPTH_RANGE, range);
        // Undo the clipped projection's depth rescaling at the viewport. Surviving terrain
        // and the normally projected player then write comparable depths, including at their feet.
        var buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        boolean shadowFlushed = false;
        try {
            GL11.glDepthRange(Mth.lerp(opening.cut(), range[0], range[1]), range[1]);
            clippedPlayer = player;
            omitModel = opening.crossesPlayer();
            try {
                drawClippedWorld.run();
                buffers.endBatch();
            } finally {
                clippedPlayer = null;
                omitModel = false;
                GL11.glDepthRange(range[0], range[1]);
            }
            RenderSystem.setProjectionMatrix(normalProjection, sorting);
            Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
            ImmersiveTerrainInterior.drawGround(player, camera.getPosition(), worldView, partialTick, opening.cut());
            // Shadow vertices are already in view space. Project them onto the finished ground once.
            if (deferredShadow != null) deferredShadow.endBatch();
            shadowFlushed = true;
            // Close the cut terrain in front of the background and its shadows. The skin can
            // extend past its collision box, so leave the real scene depth for the player draw.
            ImmersiveTerrainSection.draw(camera.getPosition(), worldView, partialTick, opening);
            if (opening.crossesPlayer()) drawModel(player, camera.getPosition(), worldView, partialTick, buffers);
        } finally {
            if (!shadowFlushed) deferredShadow = null;
            RenderSystem.setProjectionMatrix(clippedProjection, sorting);
        }
    }

    private static void drawModel(LocalPlayer player, Vec3 camera, PoseStack view, float time,
                                  MultiBufferSource.BufferSource buffers) {
        var dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        var renderer = dispatcher.getRenderer(player);
        var offset = renderer.getRenderOffset(player, time);
        double x = Mth.lerp(time, player.xOld, player.getX()) - camera.x + offset.x;
        double y = Mth.lerp(time, player.yOld, player.getY()) - camera.y + offset.y;
        double z = Mth.lerp(time, player.zOld, player.getZ()) - camera.z + offset.z;
        float yaw = Mth.lerp(time, player.yRotO, player.getYRot());
        view.pushPose();
        try {
            view.translate(x, y, z);
            // The renderer draws the real skin, cape, held items, armor and modded player layers together.
            renderer.render(player, yaw, time, view, buffers, dispatcher.getPackedLightCoords(player, time));
            // The world's separate base and foil buffers preserve the order of enchanted armor.
            buffers.endBatch();
        } finally {
            view.popPose();
        }
    }
}
