package com.quickskin.mod.client.gui.screen;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.network.chat.Component;
import com.quickskin.mod.client.gui.util.UiText;

/**
 * The cape menu's tiles, drawn with fills: the bevelled card a cape sits on, the small badges on
 * it, and the dashed outline of None. One art pixel is one GUI unit, so it stays crisp at the
 * menu's own scale.
 */
@Environment(EnvType.CLIENT)
final class CapeTileArt {
    static final int OUTLINE = 0xFF0E0E0F;

    private CapeTileArt() {
    }

    /** A card with a dark outline, a lit edge and a face. */
    static void card(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            int x, int y, int w, int h, int edge, int face) {
        graphics.fill(x, y, x + w, y + h, OUTLINE);
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, edge);
        graphics.fill(x + 2, y + 2, x + w - 2, y + h - 2, face);
    }

    /** A small text badge with its top-left corner at x, y. Returns its width. */
    static int badge(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            Font font, int x, int y, Component text, int face) {
        int w = font.width(text) + 4;
        int h = font.lineHeight + 2;
        graphics.fill(x, y, x + w, y + h, OUTLINE);
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, face);
        UiText.draw(graphics, font, text, x + 2, y + 2, 0xFFFFFFFF);
        return w;
    }

    static void dashedOutline(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            int x, int y, int w, int h, int color) {
        for (int i = 0; i < w; i += 5) {
            int e = Math.min(x + i + 3, x + w);
            graphics.fill(x + i, y, e, y + 1, color);
            graphics.fill(x + i, y + h - 1, e, y + h, color);
        }
        for (int i = 0; i < h; i += 5) {
            int e = Math.min(y + i + 3, y + h);
            graphics.fill(x, y + i, x + 1, e, color);
            graphics.fill(x + w - 1, y + i, x + w, e, color);
        }
    }

    static int lighter(int argb) {
        int a = argb >>> 24;
        int r = Math.min(255, (argb >> 16 & 0xFF) + 40);
        int g = Math.min(255, (argb >> 8 & 0xFF) + 40);
        int b = Math.min(255, (argb & 0xFF) + 40);
        return a << 24 | r << 16 | g << 8 | b;
    }
}
