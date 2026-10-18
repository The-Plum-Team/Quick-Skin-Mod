package com.quickskin.mod.client.gui.widget;

import com.mojang.blaze3d.platform.InputConstants;
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
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Quick Skin's text button, like Confirm and the dialogs' buttons: a charcoal face inside a thin
 * grey border and a black outline, on a darker lip that gives it depth. It lightens under the
 * mouse and darkens while inactive. The label uses Minecraft's font with the icons' shadow.
 */
@Environment(EnvType.CLIENT)
public class StyledButton extends Button {
    private static final int OUTLINE = 0xFF0E0E0F;
    /** Height of the darker lip under the face. */
    private static final int LIP_HEIGHT = 3;
    private static final int LABEL_MARGIN = 6;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_DISABLED = 0xFF8A8A8A;

    public StyledButton(int x, int y, int width, int height, Component label, OnPress onPress) {
        super(x, y, width, height, label, onPress, DEFAULT_NARRATION);
    }

    protected int faceColor(boolean hovered) {
        return hovered ? 0xFF4A4B4F : 0xFF3A3B3E;
    }

    protected int borderColor(boolean hovered) {
        return hovered ? 0xFF8D8E93 : 0xFF6E6F73;
    }

    protected int lipColor() {
        return 0xFF1C1D1F;
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
        int right = x + this.width;
        int bottom = y + this.height;
        graphics.fill(x, y, right, bottom, OUTLINE);
        graphics.fill(x + 1, bottom - 1 - LIP_HEIGHT, right - 1, bottom - 1, this.active ? lipColor() : 0xFF161617);
        graphics.fill(x + 1, y + 1, right - 1, bottom - 1 - LIP_HEIGHT, this.active ? borderColor(hovered) : 0xFF46474A);
        graphics.fill(x + 2, y + 2, right - 2, bottom - 2 - LIP_HEIGHT, this.active ? faceColor(hovered) : 0xFF2B2C2E);

        Font font = Minecraft.getInstance().font;
        String label = this.getMessage().getString();
        int room = this.width - 2 * LABEL_MARGIN;
        if (font.width(label) > room) {
            label = font.plainSubstrByWidth(label, Math.max(0, room - font.width("..."))) + "...";
        }
        int faceHeight = this.height - LIP_HEIGHT;
        UiText.drawCentered(graphics, font, label, x + this.width / 2, y + (faceHeight - 8) / 2,
                this.active ? TEXT : TEXT_DISABLED);
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
