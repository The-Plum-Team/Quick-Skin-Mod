package com.quickskin.mod.platform;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
//? if <26.1.2 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
//? if <1.21.11 {
import net.minecraft.client.model.PlayerModel;
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.resources.Identifier;
//?}

/**
 * Minecraft-version seam for rendering and image APIs. Loader services remain in PlatformHelper.
 */
public interface MinecraftCompat {
    //? if <1.21 {
    MinecraftCompat INSTANCE = new MinecraftCompat1_20_1();
    //?} else {
        //? if <1.21.11 {
    MinecraftCompat INSTANCE = new MinecraftCompat1_21_1();
        //?} else if <26.1.2 {
    MinecraftCompat INSTANCE = new MinecraftCompat1_21_11();
        //?} else {
    MinecraftCompat INSTANCE = new MinecraftCompat26_2();
        //?}
    //?}

    void setPixel(NativeImage image, int x, int y, int color);

    int getPixel(NativeImage image, int x, int y);

    //? if <1.21.11 {
    void setYoung(PlayerModel<?> model, boolean young);
    void setCrouching(PlayerModel<?> model, boolean crouching);
    void setRiding(PlayerModel<?> model, boolean riding);
    void setAttackTime(PlayerModel<?> model, float attackTime);
    //?} else {
    void setYoung(PlayerModel model, boolean young);
    void setCrouching(PlayerModel model, boolean crouching);
    void setRiding(PlayerModel model, boolean riding);
    void setAttackTime(PlayerModel model, float attackTime);
    //?}

    //? if <1.21.11 {
    void blit(GuiGraphics graphics, ResourceLocation texture, int x, int y, int blitOffset,
              float u, float v, int width, int height, int textureWidth, int textureHeight);
    //?} else {
        //? if <26.1.2 {
    void blit(GuiGraphics graphics, Identifier texture, int x, int y, int blitOffset,
              float u, float v, int width, int height, int textureWidth, int textureHeight);
        //?} else {
    void blit(GuiGraphicsExtractor graphics, Identifier texture, int x, int y, int blitOffset,
              float u, float v, int width, int height, int textureWidth, int textureHeight);
        //?}
    //?}

    //? if <1.21.11 {
    void blit(GuiGraphics graphics, ResourceLocation texture, int x, int y, int width, int height,
              float u, float v, int regionWidth, int regionHeight, int textureWidth, int textureHeight);
    //?} else {
        //? if <26.1.2 {
    void blit(GuiGraphics graphics, Identifier texture, int x, int y, int width, int height,
              float u, float v, int regionWidth, int regionHeight, int textureWidth, int textureHeight);
        //?} else {
    void blit(GuiGraphicsExtractor graphics, Identifier texture, int x, int y, int width, int height,
              float u, float v, int regionWidth, int regionHeight, int textureWidth, int textureHeight);
        //?}
    //?}

    //? if <1.21.11 {
    void renderCloak(PlayerModel<?> model, PoseStack poseStack, VertexConsumer vertexConsumer,
                     int packedLight, int packedOverlay);
    //?} else {
    void renderCloak(PlayerModel model, PoseStack poseStack, VertexConsumer vertexConsumer,
                     int packedLight, int packedOverlay);
    //?}
}
