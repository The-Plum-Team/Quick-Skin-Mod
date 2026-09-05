//? if <1.21.2 {
package com.quickskin.mod.platform;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.resources.ResourceLocation;

/** Immediate GUI/model APIs shared by the 1.20.1 and 1.21.1 targets. */
public final class ImmediateMinecraftCompat implements MinecraftCompat {
    @Override
    public void setPixel(NativeImage image, int x, int y, int color) {
        image.setPixelRGBA(x, y, color);
    }

    @Override
    public int getPixel(NativeImage image, int x, int y) {
        return image.getPixelRGBA(x, y);
    }

    @Override
    public void setYoung(PlayerModel<?> model, boolean young) {
        model.young = young;
    }

    @Override
    public void setCrouching(PlayerModel<?> model, boolean crouching) {
        model.crouching = crouching;
    }

    @Override
    public void setRiding(PlayerModel<?> model, boolean riding) {
        model.riding = riding;
    }

    @Override
    public void setAttackTime(PlayerModel<?> model, float attackTime) {
        model.attackTime = attackTime;
    }

    @Override
    public void blit(GuiGraphics graphics, ResourceLocation texture, int x, int y, int blitOffset,
                     float u, float v, int width, int height, int textureWidth, int textureHeight) {
        graphics.blit(texture, x, y, blitOffset, u, v, width, height, textureWidth, textureHeight);
    }

    @Override
    public void blit(GuiGraphics graphics, ResourceLocation texture, int x, int y, int width, int height,
                     float u, float v, int regionWidth, int regionHeight, int textureWidth, int textureHeight) {
        graphics.blit(texture, x, y, width, height, u, v, regionWidth, regionHeight, textureWidth, textureHeight);
    }

    @Override
    public void renderCloak(PlayerModel<?> model, PoseStack poseStack, VertexConsumer vertexConsumer,
                            int packedLight, int packedOverlay) {
        model.renderCloak(poseStack, vertexConsumer, packedLight, packedOverlay);
    }
}
//?}
