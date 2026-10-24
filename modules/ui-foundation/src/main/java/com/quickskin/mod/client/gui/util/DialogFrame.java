package com.quickskin.mod.client.gui.util;

import com.quickskin.mod.client.gui.widget.ConfirmButton;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.network.chat.Component;

/**
 * The panel of Quick Skin's dialogs: a card in the skin menu's Confirm style (a charcoal face in a
 * thin border and a black outline, on a darker lip) with a coloured header strip that holds a
 * pixel icon and the title in the Confirm label's pixel font. The header's colour says what the
 * dialog does: red to delete, blue to edit, green to upload, slate for the settings.
 */
@Environment(EnvType.CLIENT)
public final class DialogFrame {
    /** Height of the header strip, from the panel's top edge to below its accent line. */
    public static final int HEADER_HEIGHT = 26;
    /** How large the dialogs' button labels are drawn, so they leave room inside the border. */
    public static final float BUTTON_LABEL_SCALE = 0.8f;
    /** Height of the lip along the panel's bottom edge. */
    public static final int LIP_HEIGHT = 3;

    private static final int OUTLINE = 0xFF0E0E0F;
    private static final int BORDER = 0xFF6E6F73;
    private static final int FACE = 0xFF2E2F32;
    private static final int LIP = 0xFF1C1D1F;
    private static final int TITLE_COLOR = 0xFFFFFFFF;

    /** The header's strip and the line under it. */
    public enum Accent {
        DANGER(0xFF7A2020, 0xFFE04040),
        EDIT(0xFF1F3A6E, 0xFF4080FF),
        UPLOAD(0xFF1F5A2A, 0xFF40C060),
        SETTINGS(0xFF2E3A4A, 0xFF6F8FB0);

        final int strip;
        final int line;

        Accent(int strip, int line) {
            this.strip = strip;
            this.line = line;
        }
    }

    /** Nine-pixel white icons for the header. */
    public static final String[] ICON_BIN = {
            "..#####..", "#########", ".#######.", ".#.#.#.#.", ".#.#.#.#.",
            ".#.#.#.#.", ".#.#.#.#.", ".#.#.#.#.", ".#######."};
    /** The edit dialogs' pencil, in colour. */
    public static final PixelIcon ICON_PENCIL = PixelIcon.PENCIL;
    public static final String[] ICON_GEAR = {
            "....#....", ".#.###.#.", "..#####..", ".##...##.", "###...###",
            ".##...##.", "..#####..", ".#.###.#.", "....#...."};
    public static final String[] ICON_UPLOAD = {
            "....#....", "...###...", "..#####..", ".#######.", "...###...",
            "...###...", "...###...", ".........", "#########"};

    private DialogFrame() {
    }

    /** A dialog's button: Confirm's style, with its label a little smaller to leave room. */
    public static ConfirmButton button(int x, int y, int width, int height, Component label, Button.OnPress onPress) {
        ConfirmButton button = new ConfirmButton(x, y, width, height, label, onPress);
        button.setLabelScale(BUTTON_LABEL_SCALE);
        return button;
    }

    /** Draws the panel, its header, the icon and the title. The body starts at {@link #bodyTop}. */
    public static void draw(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            Font font, int x, int y, int width, int height, Accent accent, String[] icon, Component title) {
        draw(graphics, font, x, y, width, height, accent, PixelIcon.white(icon), title, FACE);
    }

    /** As {@link #draw}, with an icon of any colours. */
    public static void draw(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            Font font, int x, int y, int width, int height, Accent accent, PixelIcon icon, Component title) {
        draw(graphics, font, x, y, width, height, accent, icon, title, FACE);
    }

    /** As {@link #draw}, with the panel's face in the given colour. */
    public static void draw(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            Font font, int x, int y, int width, int height, Accent accent, String[] icon, Component title,
            int face) {
        draw(graphics, font, x, y, width, height, accent, PixelIcon.white(icon), title, face);
    }

    /** As {@link #draw}, with an icon of any colours and the panel's face in the given colour. */
    public static void draw(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            Font font, int x, int y, int width, int height, Accent accent, PixelIcon icon, Component title,
            int face) {
        int right = x + width;
        int bottom = y + height;
        graphics.fill(x, y, right, bottom, OUTLINE);
        graphics.fill(x + 1, bottom - 1 - LIP_HEIGHT, right - 1, bottom - 1, LIP);
        graphics.fill(x + 1, y + 1, right - 1, bottom - 1 - LIP_HEIGHT, BORDER);
        graphics.fill(x + 2, y + 2, right - 2, bottom - 2 - LIP_HEIGHT, face);

        // The header: the accent's strip, then a line in the accent's brighter colour
        int lineY = y + HEADER_HEIGHT - 1;
        graphics.fill(x + 2, y + 2, right - 2, lineY, accent.strip);
        graphics.fill(x + 2, lineY, right - 2, lineY + 1, accent.line);

        int middle = (y + 2 + lineY) / 2;
        int iconX = x + 10;
        int iconY = middle - icon.height() / 2;
        icon.draw(graphics, iconX, iconY);

        // The title in the pixel font, its capitals centred on the strip
        Component text = ConfirmButton.pixelText(title);
        int textX = iconX + icon.width() + 6;
        int textY = Math.round(middle + 0.5f - ConfirmButton.pixelCapitalsMiddle());
        //? if <26.1 {
        graphics.drawString(font, text, textX, textY, TITLE_COLOR, true);
        //?} else {
        graphics.text(font, text, textX, textY, TITLE_COLOR, true);
        //?}
    }

    /** The first row below the header. */
    public static int bodyTop(int y) {
        return y + HEADER_HEIGHT;
    }

    /** The last row above the lip, where the body ends. */
    public static int bodyBottom(int y, int height) {
        return y + height - 2 - LIP_HEIGHT;
    }
}
