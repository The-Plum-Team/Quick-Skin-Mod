package com.quickskin.mod.client.gui.widget;

import com.quickskin.mod.client.gui.util.UiText;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a menu's import zone, where files are dropped or a click opens the file dialog: a soft
 * panel marked at its four corners, with a tray icon over a main text and a smaller one. A zone too
 * short for the tray over the texts sets it beside them when wide enough, then drops it, then the
 * texts, and is then a bar with a plus. It lightens when highlighted. The skin menu's import tile and the cape menu's My Capes zone share it.
 */
@Environment(EnvType.CLIENT)
public final class ImportZone {
    private static final int BACKGROUND = 0x1AFFFFFF;
    private static final int BACKGROUND_HOVER = 0x28FFFFFF;
    private static final int CORNER = 0xA0FFFFFF;
    private static final int CORNER_HOVER = 0xFFFFFFFF;
    private static final int MAIN_TEXT = 0xFFE0E0E0;
    private static final int MAIN_TEXT_HOVER = 0xFFFFFFFF;
    private static final int SUB_TEXT = 0xFF909090;
    private static final int SUB_TEXT_HOVER = 0xFFB0B0B0;
    private static final int ICON_SHADOW = 0xFF2A2A2A;
    /** Length of a corner mark's arms, at most. */
    private static final int CORNER_LENGTH = 10;
    /** The tray's pixels are drawn this many GUI pixels square. */
    private static final int ICON_SCALE = 2;
    private static final int LINE_HEIGHT = 10;
    private static final int TEXT_MARGIN = 8;
    /** Space between the tray and the texts when it stands beside them. */
    private static final int ICON_GAP = 12;
    private static final String[] TRAY_ICON = {
            "....#....", "....#....", "..#.#.#..", "...###...", "....#....",
            ".........", "#.......#", "#.......#", "#########"};

    private ImportZone() {
    }

    //? if <26.1 {
    public static void draw(GuiGraphics graphics, Font font, int x, int y, int width, int height,
    //?} else {
    public static void draw(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, int height,
    //?}
                            Component mainText, Component subText, boolean highlight) {
        int right = x + width;
        int bottom = y + height;
        graphics.fill(x, y, right, bottom, highlight ? BACKGROUND_HOVER : BACKGROUND);
        drawCorners(graphics, x, y, right, bottom, highlight ? CORNER_HOVER : CORNER);

        List<String> main = wrap(font, mainText.getString(), width - 2 * TEXT_MARGIN);
        String sub = fit(font, subText.getString(), width - 2 * TEXT_MARGIN);
        int iconHeight = TRAY_ICON.length * ICON_SCALE;
        int iconWidth = TRAY_ICON[0].length() * ICON_SCALE;
        int textHeight = main.size() * LINE_HEIGHT + 4 + LINE_HEIGHT;
        int centerX = x + width / 2;

        if (height >= iconHeight + 8 + textHeight + 12) {
            // The tray over the two texts, centred together
            int top = y + (height - (iconHeight + 8 + textHeight)) / 2;
            drawIcon(graphics, centerX - TRAY_ICON[0].length() * ICON_SCALE / 2, top,
                    highlight ? MAIN_TEXT_HOVER : MAIN_TEXT);
            drawTexts(graphics, font, main, sub, centerX, top + iconHeight + 8, highlight);
        } else if (height >= Math.max(iconHeight, textHeight) + 8
                && width >= iconWidth + ICON_GAP + textWidth(font, main, sub) + 2 * TEXT_MARGIN) {
            // A wide, short zone: the tray beside the texts, centred together
            int texts = textWidth(font, main, sub);
            int left = x + (width - (iconWidth + ICON_GAP + texts)) / 2;
            drawIcon(graphics, left, y + (height - iconHeight) / 2, highlight ? MAIN_TEXT_HOVER : MAIN_TEXT);
            drawTexts(graphics, font, main, sub, left + iconWidth + ICON_GAP + texts / 2,
                    y + (height - textHeight) / 2, highlight);
        } else if (height >= textHeight + 8) {
            drawTexts(graphics, font, main, sub, centerX, y + (height - textHeight) / 2, highlight);
        } else {
            // A bar: a plus drawn from fills, so it stays crisp at every size
            int arm = Math.max(3, Math.min(width, height) / 4);
            int thickness = 2;
            int centerY = y + height / 2;
            int color = highlight ? MAIN_TEXT_HOVER : 0xFFD0D0D0;
            graphics.fill(centerX - arm, centerY - thickness / 2, centerX + arm, centerY + thickness / 2, color);
            graphics.fill(centerX - thickness / 2, centerY - arm, centerX + thickness / 2, centerY + arm, color);
        }
    }

    //? if <26.1 {
    private static void drawTexts(GuiGraphics graphics, Font font, List<String> main, String sub, int centerX, int y,
    //?} else {
    private static void drawTexts(GuiGraphicsExtractor graphics, Font font, List<String> main, String sub, int centerX, int y,
    //?}
                                  boolean highlight) {
        int lineY = y;
        for (String line : main) {
            UiText.drawCentered(graphics, font, line, centerX, lineY, highlight ? MAIN_TEXT_HOVER : MAIN_TEXT);
            lineY += LINE_HEIGHT;
        }
        UiText.drawCentered(graphics, font, sub, centerX, lineY + 4, highlight ? SUB_TEXT_HOVER : SUB_TEXT);
    }

    /** Width of the widest of the texts' lines. */
    private static int textWidth(Font font, List<String> main, String sub) {
        int widest = font.width(sub);
        for (String line : main) {
            widest = Math.max(widest, font.width(line));
        }
        return widest;
    }

    /** The tray, its pixels {@link #ICON_SCALE} GUI pixels square, over a one-pixel drop shadow. */
    //? if <26.1 {
    private static void drawIcon(GuiGraphics graphics, int x, int y, int color) {
    //?} else {
    private static void drawIcon(GuiGraphicsExtractor graphics, int x, int y, int color) {
    //?}
        for (int pass = 0; pass < 2; pass++) {
            int offset = pass == 0 ? 1 : 0;
            for (int row = 0; row < TRAY_ICON.length; row++) {
                for (int column = 0; column < TRAY_ICON[row].length(); column++) {
                    if (TRAY_ICON[row].charAt(column) == '#') {
                        int px = x + column * ICON_SCALE + offset;
                        int py = y + row * ICON_SCALE + offset;
                        graphics.fill(px, py, px + ICON_SCALE, py + ICON_SCALE, pass == 0 ? ICON_SHADOW : color);
                    }
                }
            }
        }
    }

    /**
     * An L-shaped mark in each corner, one pixel thick. The vertical arm stops short of the
     * corner pixel, which the horizontal arm draws: the colour is translucent, so a pixel drawn
     * twice would show brighter at the tip.
     */
    //? if <26.1 {
    private static void drawCorners(GuiGraphics graphics, int left, int top, int right, int bottom, int color) {
    //?} else {
    private static void drawCorners(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, int color) {
    //?}
        int length = Math.max(2, Math.min(CORNER_LENGTH, Math.min(right - left, bottom - top) / 3));
        graphics.fill(left, top, left + length, top + 1, color);
        graphics.fill(left, top + 1, left + 1, top + length, color);
        graphics.fill(right - length, top, right, top + 1, color);
        graphics.fill(right - 1, top + 1, right, top + length, color);
        graphics.fill(left, bottom - 1, left + length, bottom, color);
        graphics.fill(left, bottom - length, left + 1, bottom - 1, color);
        graphics.fill(right - length, bottom - 1, right, bottom, color);
        graphics.fill(right - 1, bottom - length, right, bottom - 1, color);
    }

    /** Splits the text into lines no wider than {@code maxWidth}, cutting a word that does not fit. */
    private static List<String> wrap(Font font, String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (font.width(candidate) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
            } else {
                if (!line.isEmpty()) {
                    lines.add(line.toString());
                }
                line.setLength(0);
                line.append(fit(font, word, maxWidth));
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    private static String fit(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("..."))) + "...";
    }

}
