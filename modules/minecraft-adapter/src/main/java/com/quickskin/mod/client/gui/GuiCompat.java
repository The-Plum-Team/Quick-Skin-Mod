package com.quickskin.mod.client.gui;

import com.quickskin.mod.platform.MinecraftCompat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.screens.Screen;
//? if <26.1 {
import net.minecraft.client.renderer.PanoramaRenderer;
//?} else {
import net.minecraft.client.renderer.Panorama;
//?}
//? if >=1.21.11 {
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
//?}
//? if >=1.21.9 {
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
//?}
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}

import java.util.List;
//? if <1.21.11 {
import java.util.Optional;
//?} else {
import java.util.stream.Collectors;
//?}

/**
 * Narrow compatibility surface for GUI APIs that change between supported Minecraft eras.
 */
@Environment(EnvType.CLIENT)
public final class GuiCompat {
    public static Screen currentScreen() {
        //? if <26.2 {
        return net.minecraft.client.Minecraft.getInstance().screen;
        //?} else {
        return net.minecraft.client.Minecraft.getInstance().gui.screen();
        //?}
    }

    public static void openScreen(Screen screen) {
        //? if <26.2 {
        net.minecraft.client.Minecraft.getInstance().setScreen(screen);
        //?} else {
        net.minecraft.client.Minecraft.getInstance().gui.setScreen(screen);
        //?}
    }

    private GuiCompat() {
    }

    //? if <26.1 {
    public static void renderParent(Screen parent, GuiGraphics graphics, float partialTick) {
        parent.render(graphics, -1, -1, partialTick);
    //?} else {
    public static void extractParent(Screen parent, GuiGraphicsExtractor graphics, float partialTick) {
        parent.extractRenderState(graphics, -1, -1, partialTick);
    //?}
    }

    //? if <1.21 {
    public static void renderPanorama(PanoramaRenderer panorama, float partialTick) {
        panorama.render(partialTick, 1.0F);
    //?} else if <1.21.6 {
    public static void renderPanorama(
            PanoramaRenderer panorama, GuiGraphics graphics, int width, int height, float partialTick) {
        panorama.render(graphics, width, height, 1.0F, partialTick);
    //?} else if <1.21.11 {
    public static void renderPanorama(
            PanoramaRenderer panorama, GuiGraphics graphics, int width, int height, float partialTick) {
        panorama.render(graphics, width, height, true);
    //?} else {
        //? if <26.1 {
    public static void renderPanorama(
            PanoramaRenderer panorama, GuiGraphics graphics, int width, int height) {
        panorama.render(graphics, width, height, true);
        //?} else {
    public static void extractPanorama(Panorama panorama, GuiGraphicsExtractor graphics, int width, int height) {
            //? if <26.2 {
        panorama.extractRenderState(graphics, width, height, true);
            //?} else {
        panorama.startSpin();
        panorama.extractRenderState(graphics, width, height);
            //?}
        //?}
    //?}
    }

    //? if <1.21.9 {
    public static double mouseX(double mouseX) {
        return mouseX;
    //?} else {
    public static double mouseX(MouseButtonEvent event) {
        return event.x();
    //?}
    }

    //? if <1.21.9 {
    public static double mouseY(double mouseY) {
        return mouseY;
    //?} else {
    public static double mouseY(MouseButtonEvent event) {
        return event.y();
    //?}
    }

    //? if <1.21.9 {
    public static int mouseButton(int button) {
        return button;
    //?} else {
    public static int mouseButton(MouseButtonEvent event) {
        return event.buttonInfo().button();
    //?}
    }

    //? if <1.21.9 {
    public static int keyCode(int keyCode) {
        return keyCode;
    //?} else {
    public static int keyCode(KeyEvent event) {
        return event.key();
    //?}
    }

    //? if <1.21.11 {
    public static void blit(GuiGraphics graphics, ResourceLocation texture, int x, int y, int blitOffset,
                            float u, float v, int width, int height, int textureWidth, int textureHeight) {
    //?} else {
        //? if <26.1 {
    public static void blit(GuiGraphics graphics, Identifier texture, int x, int y, int blitOffset,
                            float u, float v, int width, int height, int textureWidth, int textureHeight) {
        //?} else {
    public static void blit(
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
        //?}
    //?}
        MinecraftCompat.INSTANCE.blit(
                //? if <26.1 {
                graphics, texture, x, y, blitOffset, u, v, width, height, textureWidth, textureHeight);
                //?} else {
                graphics, texture, x, y, blitOffset, u, v, width, height, textureWidth, textureHeight
        );
                //?}
    }

    //? if <1.21.11 {
    public static void blit(GuiGraphics graphics, ResourceLocation texture, int x, int y, int width, int height,
                            float u, float v, int regionWidth, int regionHeight,
                            int textureWidth, int textureHeight) {
    //?} else {
        //? if <26.1 {
    public static void blit(GuiGraphics graphics, Identifier texture, int x, int y, int width, int height,
                            float u, float v, int regionWidth, int regionHeight,
                            int textureWidth, int textureHeight) {
        //?} else {
    public static void blit(
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
        //?}
    //?}
        MinecraftCompat.INSTANCE.blit(
                graphics, texture, x, y, width, height, u, v,
                //? if <26.1 {
                regionWidth, regionHeight, textureWidth, textureHeight);
                //?} else {
                regionWidth, regionHeight, textureWidth, textureHeight
        );
                //?}
    }

    //? if <1.21.6 {
    public static void tooltip(GuiGraphics graphics, Font font, Component text, int mouseX, int mouseY) {
        graphics.renderTooltip(font, text, mouseX, mouseY);
    //?} else if <1.21.11 {
    public static void tooltip(GuiGraphics graphics, Font font, Component text, int mouseX, int mouseY) {
        graphics.setTooltipForNextFrame(font, text, mouseX, mouseY);
    //?} else {
        //? if <26.1 {
    public static void tooltip(GuiGraphics graphics, Font font, Component text, int mouseX, int mouseY) {
        List<ClientTooltipComponent> components = List.of(
                ClientTooltipComponent.create(text.getVisualOrderText())
        );
        graphics.renderTooltip(font, components, mouseX, mouseY, DefaultTooltipPositioner.INSTANCE, null);
        //?} else {
    public static void tooltip(
            GuiGraphicsExtractor graphics,
            Font font,
            Component text,
            int mouseX,
            int mouseY
    ) {
        List<ClientTooltipComponent> components = List.of(
                ClientTooltipComponent.create(text.getVisualOrderText())
        );
            //? if <26.3 {
        graphics.tooltip(font, components, mouseX, mouseY, DefaultTooltipPositioner.INSTANCE, null);
            //?} else {
        graphics.tooltip(font, components, mouseX, mouseY, DefaultTooltipPositioner.INSTANCE, null, false);
            //?}
        //?}
    //?}
    }

    //? if <1.21.6 {
    public static void tooltip(GuiGraphics graphics, Font font, List<Component> lines, int mouseX, int mouseY) {
        graphics.renderTooltip(font, lines, Optional.empty(), mouseX, mouseY);
    //?} else if <1.21.11 {
    public static void tooltip(GuiGraphics graphics, Font font, List<Component> lines, int mouseX, int mouseY) {
        graphics.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
    //?} else {
        //? if <26.1 {
    public static void tooltip(GuiGraphics graphics, Font font, List<Component> lines, int mouseX, int mouseY) {
        List<ClientTooltipComponent> components = lines.stream()
                .map(line -> ClientTooltipComponent.create(line.getVisualOrderText()))
                .collect(Collectors.toList());
        graphics.renderTooltip(font, components, mouseX, mouseY, DefaultTooltipPositioner.INSTANCE, null);
        //?} else {
    public static void tooltip(
            GuiGraphicsExtractor graphics,
            Font font,
            List<Component> lines,
            int mouseX,
            int mouseY
    ) {
        List<ClientTooltipComponent> components = lines.stream()
                .map(line -> ClientTooltipComponent.create(line.getVisualOrderText()))
                .collect(Collectors.toList());
            //? if <26.3 {
        graphics.tooltip(font, components, mouseX, mouseY, DefaultTooltipPositioner.INSTANCE, null);
            //?} else {
        graphics.tooltip(font, components, mouseX, mouseY, DefaultTooltipPositioner.INSTANCE, null, false);
            //?}
        //?}
    //?}
    }

    /**
     * Draws an item's icon centred on {@code (x, y)}, {@code scaleX} and {@code scaleY} times its
     * 16 pixels: a narrower one looks turned, as a dropped item turning on the ground.
     */
    //? if <1.21.6 {
    public static void item(GuiGraphics graphics, ItemStack stack, float x, float y, float scaleX, float scaleY) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        graphics.pose().scale(scaleX, scaleY, 1.0F);
        graphics.renderItem(stack, -8, -8);
        graphics.pose().popPose();
    //?} else if <26.1 {
    public static void item(GuiGraphics graphics, ItemStack stack, float x, float y, float scaleX, float scaleY) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(scaleX, scaleY);
        graphics.renderItem(stack, -8, -8);
        graphics.pose().popMatrix();
    //?} else {
    public static void item(GuiGraphicsExtractor graphics, ItemStack stack, float x, float y, float scaleX, float scaleY) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(scaleX, scaleY);
        graphics.item(stack, -8, -8);
        graphics.pose().popMatrix();
    //?}
    }

    /**
     * Draws {@link #item} darkened to {@code brightness}, from 0 (black) to 1. Before 1.21.6 only,
     * where the GUI draws an item at once; from 1.21.6 the item is drawn as it is.
     */
    //? if <1.21.6 {
    public static void item(GuiGraphics graphics, ItemStack stack, float x, float y, float scaleX, float scaleY,
                            float brightness) {
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(brightness, brightness, brightness, 1.0F);
        try {
            item(graphics, stack, x, y, scaleX, scaleY);
        } finally {
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    //?} else if <26.1 {
    public static void item(GuiGraphics graphics, ItemStack stack, float x, float y, float scaleX, float scaleY,
                            float brightness) {
        item(graphics, stack, x, y, scaleX, scaleY);
    //?} else {
    public static void item(GuiGraphicsExtractor graphics, ItemStack stack, float x, float y, float scaleX, float scaleY,
                            float brightness) {
        item(graphics, stack, x, y, scaleX, scaleY);
    //?}
    }

    /**
     * Draws a small item icon {@code size} pixels wide centred on {@code (x, y)}, on a dark square,
     * over any item drawn there before.
     */
    //? if <1.21.6 {
    public static void badge(GuiGraphics graphics, ItemStack stack, float x, float y, float size) {
        graphics.pose().pushPose();
        // Items reach about 160 forward of where they are drawn
        graphics.pose().translate(0.0F, 0.0F, 200.0F);
    //?} else if <26.1 {
    public static void badge(GuiGraphics graphics, ItemStack stack, float x, float y, float size) {
    //?} else {
    public static void badge(GuiGraphicsExtractor graphics, ItemStack stack, float x, float y, float size) {
    //?}
        int half = Math.round(size / 2.0F) + 1;
        int left = Math.round(x) - half;
        int top = Math.round(y) - half;
        graphics.fill(left, top, left + half * 2, top + half * 2, 0xC8140A19);
        graphics.fill(left + 1, top - 1, left + half * 2 - 1, top, 0xC8140A19);
        graphics.fill(left + 1, top + half * 2, left + half * 2 - 1, top + half * 2 + 1, 0xC8140A19);
        item(graphics, stack, x, y, size / 16.0F, size / 16.0F);
    //? if <1.21.6 {
        graphics.pose().popPose();
    //?}
    }
}
