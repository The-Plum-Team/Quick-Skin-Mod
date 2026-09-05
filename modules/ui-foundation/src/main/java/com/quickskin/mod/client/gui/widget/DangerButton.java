package com.quickskin.mod.client.gui.widget;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
//? if <26.1.2 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

@Environment(EnvType.CLIENT)
public class DangerButton extends Button {
    // Red/danger button styling - matches the frosted glass theme but with red accent
    private static final int NORMAL_BG = 0xB0AA0000;         // Dark red semi-transparent background
    private static final int HOVER_BG = 0xB0DD0000;          // Brighter red on hover
    private static final int OUTLINE = 0x80FF0000;           // Red outline
    //? if <1.21.9 {
    private static final int TEXT_COLOR = 0xFFFFFFFF;        // Opaque white text
    //?} else {
    private static final int TEXT_COLOR = 0xFFFFFFFF;        // Opaque white text
    //?}

    public DangerButton(int x, int y, int width, int height, Component label, OnPress onPress) {
        super(x, y, width, height, label, onPress, DEFAULT_NARRATION);
    }

    @Override
    //? if <1.21.11 {
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
    //?} else {
        //? if <26.1.2 {
    protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        //?} else {
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
        //?}
    //?}
        // Determine background color based on hover state
        int bgColor = this.isHovered() ? HOVER_BG : NORMAL_BG;

        // Draw button background
        graphics.fill(this.getX(), this.getY(),
                     this.getX() + this.width,
                     this.getY() + this.height,
                     bgColor);

        // Draw outline
        // Top
        graphics.fill(this.getX(), this.getY(),
                     this.getX() + this.width, this.getY() + 1,
                     OUTLINE);
        // Bottom
        graphics.fill(this.getX(), this.getY() + this.height - 1,
                     this.getX() + this.width, this.getY() + this.height,
                     OUTLINE);
        // Left
        graphics.fill(this.getX(), this.getY(),
                     this.getX() + 1, this.getY() + this.height,
                     OUTLINE);
        // Right
        graphics.fill(this.getX() + this.width - 1, this.getY(),
                     this.getX() + this.width, this.getY() + this.height,
                     OUTLINE);

        // Draw centered text
        //? if <26.1.2 {
        graphics.drawCenteredString(
        //?} else {
        graphics.centeredText(
        //?}
            net.minecraft.client.Minecraft.getInstance().font,
            this.getMessage(),
            this.getX() + this.width / 2,
            this.getY() + (this.height - 8) / 2,
            TEXT_COLOR
        );
    }

    @Override
    //? if <1.21.9 {
    protected boolean isValidClickButton(int button) {
    //?} else {
    protected boolean isValidClickButton(net.minecraft.client.input.MouseButtonInfo buttonInfo) {
    //?}
        // Only allow left-click
        //? if <1.21.9 {
        return button == 0;
        //?} else {
        return buttonInfo.button() == 0;
        //?}
    }
}
