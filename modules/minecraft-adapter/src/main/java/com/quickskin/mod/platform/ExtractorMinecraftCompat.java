//? if >=26.1 {
package com.quickskin.mod.platform;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * GUI extractor implementation of rendering and image compatibility operations.
 */
public final class ExtractorMinecraftCompat implements MinecraftCompat {
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
    public void blit(
            GuiGraphicsExtractor graphics,
            Identifier texture,
            int x,
            int y,
            int blitOffset,
            float u,
            float v,
            int width,
            int height,
            int textureWidth,
            int textureHeight
    ) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, width, height, textureWidth, textureHeight);
    }

    @Override
    public void blit(
            GuiGraphicsExtractor graphics,
            Identifier texture,
            int x,
            int y,
            int width,
            int height,
            float u,
            float v,
            int regionWidth,
            int regionHeight,
            int textureWidth,
            int textureHeight
    ) {
        graphics.blit(
                RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, width, height,
                regionWidth, regionHeight, textureWidth, textureHeight
        );
    }

    private static void initializeCloakField() {
        if (cloakFieldChecked) {
            return;
        }
        cloakFieldChecked = true;

        String[] fieldNames = {"cloak", "cape"};
        for (String name : fieldNames) {
            try {
                cloakField = PlayerModel.class.getField(name);
                return;
            } catch (NoSuchFieldException ignored) {
            }
        }
        for (String name : fieldNames) {
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
    public void renderCloak(
            PlayerModel model,
            PoseStack poseStack,
            VertexConsumer vertexConsumer,
            int packedLight,
            int packedOverlay
    ) {
        initializeCloakField();
        if (cloakField != null) {
            try {
                Object cloakPart = cloakField.get(model);
                if (cloakPart != null) {
                    Method renderMethod = cloakPart.getClass().getMethod(
                            "render", PoseStack.class, VertexConsumer.class, int.class, int.class
                    );
                    renderMethod.invoke(cloakPart, poseStack, vertexConsumer, packedLight, packedOverlay);
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to render cloak ModelPart directly", e);
            }
        }
    }
}
//?}
