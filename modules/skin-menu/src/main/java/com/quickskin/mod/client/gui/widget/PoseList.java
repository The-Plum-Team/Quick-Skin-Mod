package com.quickskin.mod.client.gui.widget;

import com.mojang.blaze3d.platform.InputConstants;
import com.quickskin.mod.client.gui.util.UiText;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * The frame of the preview's pose list: a dark panel inside a grey border and a black outline.
 * It only draws; the poses are {@link Row} buttons laid over it, and it is inactive so that clicks
 * on its border fall through.
 */
@Environment(EnvType.CLIENT)
public class PoseList extends AbstractWidget {
    /** Height of one row... */
    public static final int ROW_HEIGHT = 18;
    /** ...and the outline and border around the rows, on each side. */
    public static final int FRAME = 2;
    private static final int OUTLINE = 0xFF0E0E0F;
    private static final int BORDER = 0xFF55565B;
    private static final int BODY = 0xFF26272A;

    public PoseList(int x, int y, int width, int rows) {
        super(x, y, width, rows * ROW_HEIGHT + 2 * FRAME, Component.empty());
        this.active = false;
    }

    @Override
    //? if <26.1 {
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //?} else {
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    //?}
        int left = getX();
        int top = getY();
        int right = left + this.width;
        int bottom = top + this.height;
        graphics.fill(left, top, right, bottom, OUTLINE);
        graphics.fill(left + 1, top + 1, right - 1, bottom - 1, BORDER);
        graphics.fill(left + 2, top + 2, right - 2, bottom - 2, BODY);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        // Nothing to narrate: the rows narrate themselves
    }

    /**
     * One pose: its name on the left, lighter under the mouse, and, while it is the pose shown,
     * blue with a bar on its left like a selected Settings tab.
     */
    @Environment(EnvType.CLIENT)
    public static class Row extends Button {
        private static final int HOVER = 0xFF3E3F43;
        private static final int SELECTED = 0xFF4E6078;
        private static final int SELECTED_BAR = 0xFF6F8FB0;
        private static final int TEXT = 0xFFB0B0B0;
        private static final int TEXT_BRIGHT = 0xFFFFFFFF;
        private static final int TEXT_MARGIN = 6;

        private boolean selected;

        public Row(int x, int y, int width, Component label, OnPress onPress) {
            super(x, y, width, ROW_HEIGHT, label, onPress, DEFAULT_NARRATION);
        }

        public void setSelected(boolean selected) {
            this.selected = selected;
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
            int x = getX();
            int y = getY();
            if (selected) {
                graphics.fill(x, y, x + this.width, y + this.height, SELECTED);
                graphics.fill(x, y, x + 2, y + this.height, SELECTED_BAR);
            } else if (hovered) {
                graphics.fill(x, y, x + this.width, y + this.height, HOVER);
            }
            UiText.draw(graphics, Minecraft.getInstance().font, this.getMessage(), x + TEXT_MARGIN,
                    y + (this.height - 8) / 2, selected || hovered ? TEXT_BRIGHT : TEXT);
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
}
