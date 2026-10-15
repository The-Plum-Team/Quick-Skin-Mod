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
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}

/**
 * A {@link LinkButton} drawn flat in a {@link SearchField}'s well, like {@link FieldIconButton}:
 * the skin menu's Modrinth, CurseForge, Discord and Settings buttons, on the search row. The logo
 * fills the well inside the border, so the border frames it and lightens under the mouse.
 */
@Environment(EnvType.CLIENT)
public class FieldLinkButton extends LinkButton {
    /** Room between the outside of the button and the logo: the outline and the border. */
    private static final int LOGO_INSET = 2;

    //? if <1.21.11 {
    public FieldLinkButton(int x, int y, int size, ResourceLocation logo, String url, Component tooltip) {
    //?} else {
    public FieldLinkButton(int x, int y, int size, Identifier logo, String url, Component tooltip) {
    //?}
        super(x, y, size, size, logo, url, tooltip);
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
        int x = this.getX();
        int y = this.getY();
        SearchField.drawWell(graphics, x, y, x + this.width, y + this.height, border,
                hovered ? SearchField.WELL_HOVER : SearchField.WELL);
        renderLogo(graphics, x + LOGO_INSET, y + LOGO_INSET,
                this.width - 2 * LOGO_INSET, this.height - 2 * LOGO_INSET);
    }
}
