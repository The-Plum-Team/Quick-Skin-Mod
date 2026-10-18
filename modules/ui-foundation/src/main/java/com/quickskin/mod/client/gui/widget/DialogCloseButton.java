package com.quickskin.mod.client.gui.widget;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * The close button at the right end of a dialog's header: a square in the header's slate with a
 * light border and a bold X, lightening under the mouse.
 */
@Environment(EnvType.CLIENT)
public class DialogCloseButton extends Button {
    /** The button's size, square. */
    public static final int SIZE = 17;

    private static final int OUTLINE = 0xFF0E0E0F;
    private static final int BORDER = 0xFF6F8FB0;
    private static final int BORDER_HOVER = 0xFF9DB8D6;
    private static final int FACE = 0xFF3A4656;
    private static final int FACE_HOVER = 0xFF4A5A70;
    private static final int ICON = 0xFFFFFFFF;
    private static final int ICON_SHADOW = 0xFF1A1F26;
    private static final String[] X_ICON = {
            "##.....##", "###...###", ".###.###.", "..#####..", "...###...",
            "..#####..", ".###.###.", "###...###", "##.....##"};

    public DialogCloseButton(int x, int y, Component label, OnPress onPress) {
        super(x, y, SIZE, SIZE, label, onPress, DEFAULT_NARRATION);
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
        boolean hovered = this.active && this.isHovered();
        int x = this.getX();
        int y = this.getY();
        graphics.fill(x, y, x + this.width, y + this.height, OUTLINE);
        graphics.fill(x + 1, y + 1, x + this.width - 1, y + this.height - 1, hovered ? BORDER_HOVER : BORDER);
        graphics.fill(x + 2, y + 2, x + this.width - 2, y + this.height - 2, hovered ? FACE_HOVER : FACE);
        int iconX = x + (this.width - X_ICON[0].length()) / 2;
        int iconY = y + (this.height - X_ICON.length) / 2;
        for (int pass = 0; pass < 2; pass++) {
            int offset = pass == 0 ? 1 : 0;
            for (int row = 0; row < X_ICON.length; row++) {
                for (int column = 0; column < X_ICON[row].length(); column++) {
                    if (X_ICON[row].charAt(column) == '#') {
                        int px = iconX + column + offset;
                        int py = iconY + row + offset;
                        graphics.fill(px, py, px + 1, py + 1, pass == 0 ? ICON_SHADOW : ICON);
                    }
                }
            }
        }
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
