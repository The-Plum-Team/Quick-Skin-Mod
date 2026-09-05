package com.quickskin.mod.client.gui;

import com.quickskin.mod.platform.MinecraftCompat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
//? if <26.1.2 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.screens.Screen;
//? if <26.1.2 {
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

    //? if <26.1.2 {
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
        //? if <26.1.2 {
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
        //? if <26.1.2 {
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
                //? if <26.1.2 {
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
        //? if <26.1.2 {
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
                //? if <26.1.2 {
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
        //? if <26.1.2 {
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
        graphics.tooltip(font, components, mouseX, mouseY, DefaultTooltipPositioner.INSTANCE, null);
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
        //? if <26.1.2 {
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
        graphics.tooltip(font, components, mouseX, mouseY, DefaultTooltipPositioner.INSTANCE, null);
        //?}
    //?}
    }
}
