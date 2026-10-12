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
 * The dashed "+" tile at the end of the skin column. It opens the skin file dialog; dropping files
 * anywhere on the menu imports them too.
 */
@Environment(EnvType.CLIENT)
public class ImportTileButton extends Button {

    public ImportTileButton(int x, int y, int size, OnPress onPress) {
        super(x, y, size, size, Component.translatable("quickskin.button.import_skin"), onPress, DEFAULT_NARRATION);
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
        boolean highlight = this.isHovered() && this.active;
        int x = this.getX();
        int y = this.getY();
        graphics.fill(x, y, x + this.width, y + this.height, highlight ? 0x30FFFFFF : 0x50000000);
        drawDashedBorder(graphics, x, y, this.width, this.height, highlight ? 0xFFFFFFFF : 0x90FFFFFF);

        // A plus sign drawn from fills, so it stays crisp at every size
        int arm = Math.max(4, this.width / 5);
        int thickness = 2;
        int centerX = x + this.width / 2;
        int centerY = y + this.height / 2;
        int color = highlight ? 0xFFFFFFFF : 0xFFD0D0D0;
        graphics.fill(centerX - arm, centerY - thickness / 2, centerX + arm, centerY + thickness / 2, color);
        graphics.fill(centerX - thickness / 2, centerY - arm, centerX + thickness / 2, centerY + arm, color);
    }

    //? if <26.1 {
    private static void drawDashedBorder(GuiGraphics graphics, int x, int y, int width, int height, int color) {
    //?} else {
    private static void drawDashedBorder(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color) {
    //?}
        int dashLength = 4;
        int gapLength = 3;
        int totalLength = dashLength + gapLength;
        for (int i = 0; i < width; i += totalLength) {
            int segment = Math.min(dashLength, width - i);
            graphics.fill(x + i, y, x + i + segment, y + 1, color);
            graphics.fill(x + i, y + height - 1, x + i + segment, y + height, color);
        }
        for (int i = 0; i < height; i += totalLength) {
            int segment = Math.min(dashLength, height - i);
            graphics.fill(x, y + i, x + 1, y + i + segment, color);
            graphics.fill(x + width - 1, y + i, x + width, y + i + segment, color);
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
