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
 * An {@link IconButton} drawn flat in a {@link SearchField}'s well instead of raised on a lip, for
 * a button that belongs to a field, such as the skin menu's Search. Like the field, its border
 * lightens under the mouse; it lights up fully while pressed, and dims with the icon while the
 * button is inactive. The well lightens a little under the mouse too, as only a button's does.
 */
@Environment(EnvType.CLIENT)
public class FieldIconButton extends IconButton {
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
        int top = this.getY();
        int bottom = top + this.height;
        SearchField.drawWell(graphics, this.getX(), top, this.getX() + this.width, bottom, border,
                hovered ? SearchField.WELL_HOVER : SearchField.WELL);
        // The icon in the middle of the well, inside the outline
        renderFaceContents(graphics, top + 1, bottom - 1);
    }
}
