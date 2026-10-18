package com.quickskin.mod.client.gui.widget;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}

/**
 * The drawing shared by the cape editor's panels and controls: the panels' frame, the section
 * labels' rule, the sliders and the small pixel icons, all in the colours of Quick Skin's buttons.
 */
@Environment(EnvType.CLIENT)
public final class CapeEditorStyle {
    public static final int OUTLINE = 0xFF0E0E0F;
    public static final int PANEL_BORDER = 0xFF55565B;
    public static final int PANEL_BODY = 0xFF26272A;
    public static final int RULE = 0xFF46474A;
    public static final int SECTION_TEXT = 0xFFB0B0B0;
    public static final int MUTED_TEXT = 0xFF8A8A8A;
    public static final int ACCENT = 0xFF6F8FB0;
    public static final int ICON_SHADOW = 0xFF2A2A2A;
    private static final int TRACK = 0xFF46474A;
    private static final int TRACK_DISABLED = 0xFF2B2C2E;
    private static final int KNOB = 0xFFC6C7CC;
    private static final int KNOB_HOVER = 0xFFFFFFFF;
    private static final int KNOB_DISABLED = 0xFF6E6F73;
    /** Width of a slider's knob: vanilla maps the value over the widget minus this. */
    public static final int KNOB_WIDTH = 8;

    /** 7x7 icons, a white figure with the icons' shadow below and to the right. */
    public static final String[] ICON_CENTER = {
            "...#...", "...#...", ".#####.", "##.#.##", ".#####.", "...#...", "...#..."};
    public static final String[] ICON_SNAP = {
            "#.#.#.#", ".......", "#.###.#", "..#.#..", "#.###.#", ".......", "#.#.#.#"};
    public static final String[] ICON_MIRROR = {
            "...#...", "#..#..#", "##.#.##", "###.###", "##.#.##", "#..#..#", "...#..."};
    public static final String[] ICON_PLUS = {
            "...#...", "...#...", "...#...", "#######", "...#...", "...#...", "...#..."};
    public static final String[] ICON_MINUS = {
            ".......", ".......", ".......", "#######", ".......", ".......", "......."};
    public static final String[] ICON_CAPE = {
            ".#####.", "#######", "#######", "#######", "#######", "#######", ".#####."};
    public static final String[] ICON_WINGS = {
            "##...##", "###.###", "###.###", "###.###", ".##.##.", ".##.##.", "..#.#.."};
    public static final int ICON_SIZE = 7;

    private CapeEditorStyle() {
    }

    /** A panel: black outline, grey border and a dark body. */
    //? if <26.1 {
    public static void panel(GuiGraphics graphics, int x0, int y0, int x1, int y1) {
    //?} else {
    public static void panel(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1) {
    //?}
        graphics.fill(x0, y0, x1, y1, OUTLINE);
        graphics.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, PANEL_BORDER);
        graphics.fill(x0 + 2, y0 + 2, x1 - 2, y1 - 2, PANEL_BODY);
    }

    /** One of {@link #ICON_CENTER} and the rest, with its shadow. */
    //? if <26.1 {
    public static void icon(GuiGraphics graphics, String[] rows, int x, int y, int color) {
    //?} else {
    public static void icon(GuiGraphicsExtractor graphics, String[] rows, int x, int y, int color) {
    //?}
        int shadow = (color & 0xFF000000) | (ICON_SHADOW & 0x00FFFFFF);
        for (int pass = 0; pass < 2; pass++) {
            int offset = pass == 0 ? 1 : 0;
            int fill = pass == 0 ? shadow : color;
            for (int row = 0; row < rows.length; row++) {
                String line = rows[row];
                for (int column = 0; column < line.length(); column++) {
                    if (line.charAt(column) == '#') {
                        int px = x + column + offset;
                        int py = y + row + offset;
                        graphics.fill(px, py, px + 1, py + 1, fill);
                    }
                }
            }
        }
    }

    /**
     * A slider over its whole rectangle: a thin track, lit up to the knob, and a light knob where
     * vanilla's own mapping of {@code value} puts it, so a click lands where the knob is drawn.
     */
    //? if <26.1 {
    public static void slider(GuiGraphics graphics, int x, int y, int width, int height, double value,
    //?} else {
    public static void slider(GuiGraphicsExtractor graphics, int x, int y, int width, int height, double value,
    //?}
                              boolean hovered, boolean active) {
        int middle = y + height / 2;
        int knobX = x + (int) (value * (width - KNOB_WIDTH));
        graphics.fill(x, middle - 2, x + width, middle + 2, OUTLINE);
        graphics.fill(x + 1, middle - 1, x + width - 1, middle + 1, active ? TRACK : TRACK_DISABLED);
        if (active) {
            graphics.fill(x + 1, middle - 1, knobX + KNOB_WIDTH / 2, middle + 1, ACCENT);
        }
        graphics.fill(knobX, y, knobX + KNOB_WIDTH, y + height, OUTLINE);
        graphics.fill(knobX + 1, y + 1, knobX + KNOB_WIDTH - 1, y + height - 1,
                !active ? KNOB_DISABLED : hovered ? KNOB_HOVER : KNOB);
    }
}
