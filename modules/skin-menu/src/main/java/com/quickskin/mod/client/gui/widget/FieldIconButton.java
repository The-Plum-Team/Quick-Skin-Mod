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
 * An {@link IconButton} drawn flat beside a {@link SearchField}, for a button that belongs to a
 * field, such as the skin menu's Search: Confirm's charcoal face and border without the lip, so
 * it stands level with the field instead of sinking into a well like it. The border and face
 * lighten under the mouse, the border lights up fully while pressed, and both dim with the icon
 * while the button is inactive.
 */
@Environment(EnvType.CLIENT)
public class FieldIconButton extends IconButton {
    // Confirm's colours
    private static final int OUTLINE = 0xFF0E0E0F;
    private static final int FACE = 0xFF3A3B3E;
    private static final int FACE_HOVER = 0xFF4A4B4F;
    private static final int FACE_DISABLED = 0xFF2B2C2E;

    public FieldIconButton(int x, int y, int size, String iconName, Component label, OnPress onPress) {
        super(x, y, size, iconName, label, onPress);
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
        int border = !this.active ? SearchField.BORDER_DISABLED
                : pressed ? SearchField.BORDER_FOCUSED
                : hovered ? SearchField.BORDER_HOVER : SearchField.BORDER;
        int face = !this.active ? FACE_DISABLED : hovered ? FACE_HOVER : FACE;
        int left = this.getX();
        int right = left + this.width;
        int top = this.getY();
        int bottom = top + this.height;
        // The outline, a thin border, then the face inside it
        graphics.fill(left, top, right, bottom, OUTLINE);
        graphics.fill(left + 1, top + 1, right - 1, bottom - 1, border);
        graphics.fill(left + 2, top + 2, right - 2, bottom - 2, face);
        // The icon in the middle of the face, inside the outline
        renderFaceContents(graphics, top + 1, bottom - 1);
    }
}
