package com.quickskin.mod.client.gui.widget;

import com.quickskin.mod.client.gui.util.UiText;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.network.chat.Component;

/**
 * One entry of the cape editor's guide legend, in the bottom-left corner of the canvas: a square in
 * the colour of a guide's outline and the guide's name. While {@link #setShown shown} its square is
 * filled and its border takes the guide's colour; a click shows or hides that guide. The Front
 * guide's entry is always shown and is drawn by the screen through the static {@code draw}, not as
 * a button.
 */
@Environment(EnvType.CLIENT)
public class CapeGuideChip extends CapeEditorButton {
    public static final int HEIGHT = 18;
    private static final int LIP_HEIGHT = 2;
    private static final int SWATCH = 7;
    private static final int GAP = 4;
    private static final int MARGIN = 6;
    private static final int TEXT_ON = 0xFFFFFFFF;
    private static final int TEXT_OFF = 0xFF8A8A8A;

    private final String label;
    private final int color;
    private boolean shown;

    public CapeGuideChip(int x, int y, int width, String label, int color, Component message, OnPress onPress) {
        super(x, y, width, HEIGHT, message, onPress);
        this.label = label;
        this.color = color;
    }

    /** The width of an entry naming {@code label}, or of its square alone for an empty label. */
    public static int width(Font font, String label) {
        return MARGIN + SWATCH + (label.isEmpty() ? 0 : GAP + font.width(label)) + MARGIN;
    }

    public void setShown(boolean shown) {
        this.shown = shown;
    }

    public boolean isShown() {
        return shown;
    }

    @Override
    //? if <26.1 {
    protected void draw(GuiGraphics graphics, boolean hovered) {
    //?} else {
    protected void draw(GuiGraphicsExtractor graphics, boolean hovered) {
    //?}
        draw(graphics, Minecraft.getInstance().font, getX(), getY(), this.width, this.height, label, color,
                shown, hovered);
    }

    /** An entry at its place, lit when {@code shown}, lighter while hovered. */
    //? if <26.1 {
    public static void draw(GuiGraphics graphics, Font font, int x, int y, int width, int height,
    //?} else {
    public static void draw(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, int height,
    //?}
                            String label, int color, boolean shown, boolean hovered) {
        int right = x + width;
        int bottom = y + height;
        int face = shown ? (hovered ? 0xFF4A4B4F : 0xFF3A3B3E) : (hovered ? 0xFF35363A : 0xFF232427);
        int border = shown ? color : (hovered ? 0xFF8D8E93 : 0xFF55565B);
        graphics.fill(x, y, right, bottom, CapeEditorStyle.OUTLINE);
        graphics.fill(x + 1, bottom - 1 - LIP_HEIGHT, right - 1, bottom - 1, 0xFF1C1D1F);
        graphics.fill(x + 1, y + 1, right - 1, bottom - 1 - LIP_HEIGHT, border);
        graphics.fill(x + 2, y + 2, right - 2, bottom - 2 - LIP_HEIGHT, face);

        int middle = y + (height - LIP_HEIGHT) / 2;
        int swatchX = x + MARGIN;
        int swatchY = middle - SWATCH / 2;
        if (shown) {
            graphics.fill(swatchX, swatchY, swatchX + SWATCH, swatchY + SWATCH, color);
        } else {
            int dim = TEXT_OFF;
            graphics.fill(swatchX, swatchY, swatchX + SWATCH, swatchY + 1, dim);
            graphics.fill(swatchX, swatchY + SWATCH - 1, swatchX + SWATCH, swatchY + SWATCH, dim);
            graphics.fill(swatchX, swatchY + 1, swatchX + 1, swatchY + SWATCH - 1, dim);
            graphics.fill(swatchX + SWATCH - 1, swatchY + 1, swatchX + SWATCH, swatchY + SWATCH - 1, dim);
        }
        if (!label.isEmpty()) {
            UiText.draw(graphics, font, label, swatchX + SWATCH + GAP, middle - 4, shown ? TEXT_ON : TEXT_OFF);
        }
    }
}
