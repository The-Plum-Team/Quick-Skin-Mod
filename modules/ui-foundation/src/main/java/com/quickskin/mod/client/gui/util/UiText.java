package com.quickskin.mod.client.gui.util;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.network.chat.Component;

/**
 * Text in Quick Skin's own screens, with the drop shadow of their icons: the same dark grey one
 * pixel down and right. Minecraft's own shadow is the text's colour at a quarter of its
 * brightness, which on these dark faces is almost the face's own colour and disappears.
 */
@Environment(EnvType.CLIENT)
public final class UiText {
    /** The icons' shadow grey; the text's own alpha is kept so fading text fades with it. */
    private static final int SHADOW = 0xFF2A2A2A;

    private UiText() {
    }

    //? if <26.1 {
    public static void draw(GuiGraphics graphics, Font font, String text, int x, int y, int color) {
    //?} else {
    public static void draw(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int color) {
    //?}
        draw(graphics, font, Component.literal(text), x, y, color);
    }

    //? if <26.1 {
    public static void draw(GuiGraphics graphics, Font font, Component text, int x, int y, int color) {
    //?} else {
    public static void draw(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int color) {
    //?}
        int shadow = (color & 0xFF000000) | (SHADOW & 0x00FFFFFF);
        //? if <26.1 {
        graphics.drawString(font, text, x + 1, y + 1, shadow, false);
        //?} else {
        graphics.text(font, text, x + 1, y + 1, shadow, false);
        //?}
        //? if <1.21.6 {
        // Drawn at the same depth the shadow could cover the text where the glyphs overlap
        graphics.pose().pushPose();
        graphics.pose().translate(0.0f, 0.0f, 0.03f);
        graphics.drawString(font, text, x, y, color, false);
        graphics.pose().popPose();
        //?} else if <26.1 {
        graphics.drawString(font, text, x, y, color, false);
        //?} else {
        graphics.text(font, text, x, y, color, false);
        //?}
    }

    /** Draws {@code text} centred on {@code centerX}. */
    //? if <26.1 {
    public static void drawCentered(GuiGraphics graphics, Font font, String text, int centerX, int y, int color) {
    //?} else {
    public static void drawCentered(GuiGraphicsExtractor graphics, Font font, String text, int centerX, int y, int color) {
    //?}
        draw(graphics, font, text, centerX - font.width(text) / 2, y, color);
    }

    //? if <26.1 {
    public static void drawCentered(GuiGraphics graphics, Font font, Component text, int centerX, int y, int color) {
    //?} else {
    public static void drawCentered(GuiGraphicsExtractor graphics, Font font, Component text, int centerX, int y, int color) {
    //?}
        draw(graphics, font, text, centerX - font.width(text) / 2, y, color);
    }
}
