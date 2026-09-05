//? if >=1.21.2 && <1.21.6 {
package com.quickskin.mod.platform;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/** RenderType GUI APIs shared by the 1.21.2 through 1.21.5 targets. */
public final class RenderTypeMinecraftCompat implements MinecraftCompat {
    private static int abgrToArgb(int abgr) {
        int a = (abgr >>> 24) & 0xFF;
        int b = (abgr >>> 16) & 0xFF;
        int g = (abgr >>> 8) & 0xFF;
        int r = abgr & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    @Override
    public void setPixel(NativeImage image, int x, int y, int color) {
        image.setPixel(x, y, abgrToArgb(color));
    }

    @Override
    public int getPixel(NativeImage image, int x, int y) {
        return abgrToArgb(image.getPixel(x, y));
    }

    @Override
    public void setYoung(PlayerModel model, boolean young) {
    }

    @Override
    public void setCrouching(PlayerModel model, boolean crouching) {
    }

    @Override
    public void setRiding(PlayerModel model, boolean riding) {
    }

    @Override
    public void setAttackTime(PlayerModel model, float attackTime) {
    }

    @Override
    public void blit(GuiGraphics graphics, ResourceLocation texture, int x, int y, int blitOffset,
                     float u, float v, int width, int height, int textureWidth, int textureHeight) {
        graphics.blit(RenderType::guiTextured, texture, x, y, u, v,
                width, height, textureWidth, textureHeight);
    }

    @Override
    public void blit(GuiGraphics graphics, ResourceLocation texture, int x, int y, int width, int height,
                     float u, float v, int regionWidth, int regionHeight,
                     int textureWidth, int textureHeight) {
        graphics.blit(RenderType::guiTextured, texture, x, y, u, v,
                width, height, regionWidth, regionHeight, textureWidth, textureHeight);
    }

    @Override
    public void renderCloak(PlayerModel model, PoseStack poseStack, VertexConsumer vertexConsumer,
                            int packedLight, int packedOverlay) {
        // Preview rendering owns the separate PlayerCapeModel introduced with render states.
    }
}
//?}
