package com.quickskin.mod.client.gui.widget;

import com.quickskin.mod.client.gui.util.UiText;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The skin column's import zone, under the last skin. It opens the skin file dialog; dropping
 * files anywhere on the menu imports them too.
 *
 * <p>It fills the column's free space: a soft panel marked at its four corners, with a tray icon,
 * "Drag skin files here" and "or click to import". With too little room for those it is a bar at
 * the column's bottom with a plus. It lightens under the mouse.
 */
@Environment(EnvType.CLIENT)
public class ImportTileButton extends Button {
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
    private static final String[] TRAY_ICON = {
            "....#....", "....#....", "..#.#.#..", "...###...", "....#....",
            ".........", "#.......#", "#.......#", "#########"};

    public ImportTileButton(int x, int y, int width, int height, OnPress onPress) {
        super(x, y, width, height, Component.translatable("quickskin.button.import_skin"), onPress, DEFAULT_NARRATION);
    }

    /** Moves the zone's top edge and sets its height, as the free space under the skins changes. */
    public void setBounds(int y, int height) {
        setY(y);
        this.height = Math.max(1, height);
    }

    @Override
    //? if <1.21.11 {
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
    //?} else {
        //? if <26.1 {
    protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        //?} else {
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
        //?}
    //?}
        boolean highlight = this.isHovered() && this.active;
        int x = this.getX();
        int y = this.getY();
        int right = x + this.width;
        int bottom = y + this.height;
        graphics.fill(x, y, right, bottom, highlight ? BACKGROUND_HOVER : BACKGROUND);
        drawCorners(graphics, x, y, right, bottom, highlight ? CORNER_HOVER : CORNER);

        Font font = Minecraft.getInstance().font;
        List<String> main = wrap(font, Component.translatable("quickskin.dropzone.skins.main").getString(),
                this.width - 2 * TEXT_MARGIN);
        String sub = fit(font, Component.translatable("quickskin.dropzone.skins.click").getString(),
                this.width - 2 * TEXT_MARGIN);
        int iconHeight = TRAY_ICON.length * ICON_SCALE;
        int textHeight = main.size() * LINE_HEIGHT + 4 + LINE_HEIGHT;
        int centerX = x + this.width / 2;

        if (this.height >= iconHeight + 8 + textHeight + 12) {
            // The tray over the two texts, centred together
            int top = y + (this.height - (iconHeight + 8 + textHeight)) / 2;
            drawIcon(graphics, centerX - TRAY_ICON[0].length() * ICON_SCALE / 2, top,
                    highlight ? MAIN_TEXT_HOVER : MAIN_TEXT);
            drawTexts(graphics, font, main, sub, centerX, top + iconHeight + 8, highlight);
        } else if (this.height >= textHeight + 8) {
            drawTexts(graphics, font, main, sub, centerX, y + (this.height - textHeight) / 2, highlight);
        } else {
            // A bar: a plus drawn from fills, so it stays crisp at every size
            int arm = Math.max(3, Math.min(this.width, this.height) / 4);
            int thickness = 2;
            int centerY = y + this.height / 2;
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

    /** An L-shaped mark in each corner, one pixel thick. */
    //? if <26.1 {
    private static void drawCorners(GuiGraphics graphics, int left, int top, int right, int bottom, int color) {
    //?} else {
    private static void drawCorners(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, int color) {
    //?}
        int length = Math.max(2, Math.min(CORNER_LENGTH, Math.min(right - left, bottom - top) / 3));
        graphics.fill(left, top, left + length, top + 1, color);
        graphics.fill(left, top, left + 1, top + length, color);
        graphics.fill(right - length, top, right, top + 1, color);
        graphics.fill(right - 1, top, right, top + length, color);
        graphics.fill(left, bottom - 1, left + length, bottom, color);
        graphics.fill(left, bottom - length, left + 1, bottom, color);
        graphics.fill(right - length, bottom - 1, right, bottom, color);
        graphics.fill(right - 1, bottom - length, right, bottom, color);
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

    @Override
    //? if <1.21.9 {
    protected boolean isValidClickButton(int button) {
        return button == InputConstants.MOUSE_BUTTON_LEFT;
    //?} else {
    protected boolean isValidClickButton(net.minecraft.client.input.MouseButtonInfo buttonInfo) {
        return buttonInfo.button() == InputConstants.MOUSE_BUTTON_LEFT;
    //?}
    }
}
