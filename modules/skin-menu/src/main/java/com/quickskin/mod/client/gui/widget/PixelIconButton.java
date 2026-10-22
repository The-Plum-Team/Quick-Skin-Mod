package com.quickskin.mod.client.gui.widget;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.network.chat.Component;

/**
 * A small button that shows a pixel icon instead of its label, such as the skin column's delete
 * mode, view and sort buttons. It is drawn like Confirm on a lip of a single pixel: the face
 * lightens under the mouse and sinks onto the lip while pressed. A {@link #setDanger danger}
 * button, such as the delete mode while it is on, has a red border on a dark red face. The icon
 * is drawn from fills, one GUI pixel per {@code '#'}, centred with a dark drop shadow, and greyed
 * out while the button is inactive. The label is still the button's message, for its narration.
 */
@Environment(EnvType.CLIENT)
public class PixelIconButton extends ConfirmButton {
    private static final int ICON_COLOR = 0xFFFFFFFF;
    private static final int DISABLED_COLOR = 0xFFA0A0A0;
    /** The icons' drop shadow, the same grey as in the icon textures of the buttons by the model. */
    private static final int SHADOW_COLOR = 0xFF2A2A2A;
    // Confirm's colours, and the delete mode's reds
    private static final int OUTLINE = 0xFF0E0E0F;
    private static final int FACE = 0xFF3A3B3E;
    private static final int FACE_HOVER = 0xFF4A4B4F;
    private static final int FACE_DISABLED = 0xFF2B2C2E;
    private static final int BORDER = 0xFF6E6F73;
    private static final int BORDER_HOVER = 0xFF8D8E93;
    private static final int BORDER_DISABLED = 0xFF46474A;
    private static final int LIP = 0xFF1C1D1F;
    private static final int DANGER_FACE = 0xFF5A2222;
    private static final int DANGER_FACE_HOVER = 0xFF6E2A2A;
    private static final int DANGER_BORDER = 0xFFE04040;
    private static final int DANGER_BORDER_HOVER = 0xFFFF9090;
    /** Height of the lip under the face, which the face sinks onto while pressed. */
    private static final int LIP_HEIGHT = 1;

    private String[] icon;

    /** A button showing an icon given as rows of {@code '#'} and {@code '.'}. */
    public PixelIconButton(int x, int y, int width, int height, String[] icon, Component label, OnPress onPress) {
        super(x, y, width, height, label, onPress);
        this.icon = icon;
    }

    public void setIcon(String[] icon) {
        this.icon = icon;
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
        boolean pressed = hovered && Minecraft.getInstance().mouseHandler.isLeftPressed();
        int border;
        int face;
        if (!this.active) {
            border = BORDER_DISABLED;
            face = FACE_DISABLED;
        } else if (isDanger()) {
            border = hovered ? DANGER_BORDER_HOVER : DANGER_BORDER;
            face = hovered ? DANGER_FACE_HOVER : DANGER_FACE;
        } else {
            border = hovered ? BORDER_HOVER : BORDER;
            face = hovered ? FACE_HOVER : FACE;
        }
        int left = this.getX();
        int right = left + this.width;
        int bottom = this.getY() + this.height;
        int sink = pressed ? LIP_HEIGHT : 0;
        int faceTop = this.getY() + 1 + sink;
        int faceBottom = bottom - 1 - LIP_HEIGHT + sink;
        // The outline, the lip along the bottom, then the face resting on it inside a thin border
        graphics.fill(left, this.getY(), right, bottom, OUTLINE);
        if (!pressed) {
            graphics.fill(left + 1, faceBottom, right - 1, bottom - 1, LIP);
        }
        graphics.fill(left + 1, faceTop, right - 1, faceBottom, border);
        graphics.fill(left + 2, faceTop + 1, right - 2, faceBottom - 1, face);
        renderFaceContents(graphics, faceTop, faceBottom);
    }

    @Override
    //? if <26.1 {
    protected void renderFaceContents(GuiGraphics graphics, int faceTop, int faceBottom) {
    //?} else {
    protected void renderFaceContents(GuiGraphicsExtractor graphics, int faceTop, int faceBottom) {
    //?}
        int iconWidth = icon.length == 0 ? 0 : icon[0].length();
        int x = this.getX() + (this.width - iconWidth) / 2;
        int y = faceTop + (faceBottom - faceTop - icon.length) / 2;
        int color = this.active ? ICON_COLOR : DISABLED_COLOR;
        for (int pass = 0; pass < 2; pass++) {
            int offset = pass == 0 ? 1 : 0;
            for (int row = 0; row < icon.length; row++) {
                for (int column = 0; column < icon[row].length(); column++) {
                    if (icon[row].charAt(column) == '#') {
                        int px = x + column + offset;
                        int py = y + row + offset;
                        graphics.fill(px, py, px + 1, py + 1, pass == 0 ? SHADOW_COLOR : color);
                    }
                }
            }
        }
    }
}
