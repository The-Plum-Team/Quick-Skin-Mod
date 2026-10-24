package com.quickskin.mod.client.gui.util;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}

/**
 * A small icon drawn pixel by pixel from rows of characters, each character a colour of its
 * palette and {@code '.'} transparent, optionally over a shadow of its silhouette one pixel right
 * and down. One icon pixel is one unit of the current pose, so a scaled pose draws it larger.
 */
@Environment(EnvType.CLIENT)
public final class PixelIcon {
    private static final int SHADOW = 0xFF2A2A2A;

    private static final String PENCIL_KEYS = "OGWwLYDMmPp";
    private static final int[] PENCIL_COLORS = {
            0xFF2B1E1E, // outline
            0xFF000000, // graphite
            0xFFF6CF95, 0xFFD9A562, // wood, lit and shaded
            0xFFFFF08A, 0xFFFFC83A, 0xFFE88A1E, // body: highlight, yellow, shade
            0xFFE4E6EE, 0xFFA3A7B5, // ferrule, lit and shaded
            0xFFFF9DB4, 0xFFE8668A}; // eraser, lit and shaded

    /** The yellow pencil of the edit dialogs' headers, 12 pixels square. */
    public static final PixelIcon PENCIL = new PixelIcon(new String[]{
            "........OO..",
            ".......OPPO.",
            "......OMPPpO",
            ".....OLMMppO",
            "....OLYYmmO.",
            "...OLYYDDO..",
            "..OLYYDDO...",
            ".OLYYDDO....",
            "OWWYDDO.....",
            "OWWwDO......",
            "GWwwO.......",
            "GGOO........"}, PENCIL_KEYS, PENCIL_COLORS, 0);

    /** The same pencil in 10 pixels, for drawing larger, as beside the skin menu's name tag. */
    public static final PixelIcon PENCIL_COMPACT = new PixelIcon(new String[]{
            "......OO..",
            ".....OPPO.",
            "....OMPPpO",
            "...OLMMppO",
            "..OLYYmmO.",
            ".OLYYDDO..",
            "OWYYDDO...",
            "OWWDDO....",
            "GWwwO.....",
            "GGOO......"}, PENCIL_KEYS, PENCIL_COLORS, 0);

    private final String[] rows;
    private final String keys;
    private final int[] colors;
    private final int shadow;

    private PixelIcon(String[] rows, String keys, int[] colors, int shadow) {
        this.rows = rows;
        this.keys = keys;
        this.colors = colors;
        this.shadow = shadow;
    }

    /** A white icon with the dark shadow of the menus' monochrome icons, its pixels marked {@code '#'}. */
    public static PixelIcon white(String[] rows) {
        return new PixelIcon(rows, "#", new int[]{0xFFFFFFFF}, SHADOW);
    }

    public int width() {
        return rows.length == 0 ? 0 : rows[0].length();
    }

    public int height() {
        return rows.length;
    }

    /** Draws the icon with its top left at {@code x, y}. */
    public void draw(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            int x, int y) {
        if (shadow != 0) {
            for (int row = 0; row < rows.length; row++) {
                for (int column = 0; column < rows[row].length(); column++) {
                    if (keys.indexOf(rows[row].charAt(column)) >= 0) {
                        graphics.fill(x + column + 1, y + row + 1, x + column + 2, y + row + 2, shadow);
                    }
                }
            }
        }
        for (int row = 0; row < rows.length; row++) {
            for (int column = 0; column < rows[row].length(); column++) {
                int key = keys.indexOf(rows[row].charAt(column));
                if (key >= 0) {
                    graphics.fill(x + column, y + row, x + column + 1, y + row + 1, colors[key]);
                }
            }
        }
    }
}
