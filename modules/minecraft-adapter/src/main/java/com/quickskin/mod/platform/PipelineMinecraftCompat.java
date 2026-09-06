//? if >=1.21.6 && <26.1 {
package com.quickskin.mod.platform;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
//? if <1.21.11 {
import net.minecraft.client.model.PlayerModel;
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.resources.Identifier;
//?}
import net.minecraft.client.renderer.RenderPipelines;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** RenderPipeline GUI APIs before Minecraft switched to GUI extraction. */
public final class PipelineMinecraftCompat implements MinecraftCompat {
    private static Field cloakField;
    private static boolean cloakFieldChecked;

    private static int abgrToArgb(int abgr) {
        int a = (abgr >> 24) & 0xFF;
        int b = (abgr >> 16) & 0xFF;
        int g = (abgr >> 8) & 0xFF;
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
    //? if <1.21.11 {
    public void blit(GuiGraphics graphics, ResourceLocation texture, int x, int y, int blitOffset,
    //?} else {
    public void blit(GuiGraphics graphics, Identifier texture, int x, int y, int blitOffset,
    //?}
                     float u, float v, int width, int height, int textureWidth, int textureHeight) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, width, height, textureWidth, textureHeight);
    }

    @Override
    //? if <1.21.11 {
    public void blit(GuiGraphics graphics, ResourceLocation texture, int x, int y, int width, int height,
    //?} else {
    public void blit(GuiGraphics graphics, Identifier texture, int x, int y, int width, int height,
    //?}
                     float u, float v, int regionWidth, int regionHeight,
                     int textureWidth, int textureHeight) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, width, height,
                regionWidth, regionHeight, textureWidth, textureHeight);
    }

    private static void initializeCloakField() {
        if (cloakFieldChecked) return;
        cloakFieldChecked = true;
        for (String name : new String[]{"cloak", "cape"}) {
            try {
                cloakField = PlayerModel.class.getField(name);
                return;
            } catch (NoSuchFieldException ignored) {
            }
        }
        for (String name : new String[]{"cloak", "cape"}) {
            try {
                cloakField = PlayerModel.class.getDeclaredField(name);
                cloakField.setAccessible(true);
                return;
            } catch (NoSuchFieldException ignored) {
            }
        }
        cloakField = null;
    }

    @Override
    public void renderCloak(PlayerModel model, PoseStack poseStack, VertexConsumer vertexConsumer,
                            int packedLight, int packedOverlay) {
        initializeCloakField();
        if (cloakField == null) return;
        try {
            Object cloakPart = cloakField.get(model);
            if (cloakPart != null) {
                Method renderMethod = cloakPart.getClass().getMethod(
                        "render", PoseStack.class, VertexConsumer.class, int.class, int.class);
                renderMethod.invoke(cloakPart, poseStack, vertexConsumer, packedLight, packedOverlay);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to render cloak ModelPart directly", e);
        }
    }
}
//?}
