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
public class TabButton extends Button {
    private boolean selected;

    // Tab styling - matches the frosted glass theme
    private static final int SELECTED_BG = 0xB0000000;      // Darker semi-transparent background
    private static final int UNSELECTED_BG = 0x60000000;    // Lighter semi-transparent background
    private static final int SELECTED_OUTLINE = 0xFFFFFFFF; // White outline for selected
    private static final int UNSELECTED_OUTLINE = 0x40FFFFFF; // Faint outline for unselected
    //? if <1.21.9 {
    private static final int SELECTED_TEXT = 0xFFFFFFFF;    // Opaque white text
    private static final int UNSELECTED_TEXT = 0xFF999999;  // Opaque gray text
    //?} else {
    private static final int SELECTED_TEXT = 0xFFFFFFFF;    // Opaque white text
    private static final int UNSELECTED_TEXT = 0xFF999999;  // Opaque gray text
    //?}

    public TabButton(int x, int y, int width, int height, Component label, boolean selected, OnPress onPress) {
        super(x, y, width, height, label, onPress, DEFAULT_NARRATION);
        this.selected = selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public boolean isSelected() {
        return this.selected;
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
        // Determine colors based on selected state
        int bgColor = this.selected ? SELECTED_BG : UNSELECTED_BG;
        int outlineColor = this.selected ? SELECTED_OUTLINE : UNSELECTED_OUTLINE;
        int textColor = this.selected ? SELECTED_TEXT : UNSELECTED_TEXT;

        // Add hover effect for unselected tabs
        if (!this.selected && this.isHovered()) {
            bgColor = 0x80000000; // Slightly darker on hover
            //? if <1.21.9 {
            textColor = 0xFFCCCCCC;  // Slightly brighter opaque text on hover
            //?} else {
            textColor = 0xFFCCCCCC;  // Slightly brighter opaque text on hover
            //?}
        }

        // Draw tab background
        graphics.fill(this.getX(), this.getY(),
                     this.getX() + this.width,
                     this.getY() + this.height,
                     bgColor);

        // Draw outline
        // Top
        graphics.fill(this.getX(), this.getY(),
                     this.getX() + this.width, this.getY() + 1,
                     outlineColor);
        // Left
        graphics.fill(this.getX(), this.getY(),
                     this.getX() + 1, this.getY() + this.height,
                     outlineColor);
        // Right
        graphics.fill(this.getX() + this.width - 1, this.getY(),
                     this.getX() + this.width, this.getY() + this.height,
                     outlineColor);

        // For selected tab, don't draw bottom border (connects to content area)
        if (!this.selected) {
            graphics.fill(this.getX(), this.getY() + this.height - 1,
                         this.getX() + this.width, this.getY() + this.height,
                         outlineColor);
        }

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
            textColor
        );
    }

    @Override
    //? if <1.21.11 {
    protected boolean isValidClickButton(int button) {
    //?} else {
    protected boolean isValidClickButton(net.minecraft.client.input.MouseButtonInfo buttonInfo) {
    //?}
        // Only allow left-click
        //? if <1.21.11 {
        return button == 0;
        //?} else {
        return buttonInfo.button() == 0;
        //?}
    }
}
