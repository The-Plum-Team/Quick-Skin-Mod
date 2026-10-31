package com.quickskin.mod.client.gui.widget;

import com.quickskin.mod.client.gui.util.UiText;
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
 * A tab of the settings dialog's sidebar: a row across the sidebar with a pixel icon and the tab's
 * name, left-aligned. The selected tab is dark blue with a blue bar along its left edge and a notch
 * in its right edge, cut in the colour of the settings beside it so the tab points at them; an
 * unselected tab is plain, lightening under the mouse.
 */
@Environment(EnvType.CLIENT)
public class TabButton extends Button {
    private boolean selected;
    private String[] icon = new String[0];
    private int notchColor;

    private static final int SELECTED_BG = 0xFF2F3A4F;
    private static final int HOVER_BG = 0xFF3C3D42;
    private static final int SELECTED_BAR = 0xFF4080FF;
    private static final int SELECTED_TEXT = 0xFFFFFFFF;
    private static final int HOVER_TEXT = 0xFFFFFFFF;
    private static final int UNSELECTED_TEXT = 0xFFB0B0B0;
    private static final int ICON_SHADOW = 0xFF2A2A2A;
    private static final int BAR_WIDTH = 2;
    /** Half the height of the notch, which is also how deep it cuts. */
    private static final int NOTCH = 4;

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

    /**
     * The colour of what lies right of the tab. The selected tab's notch is cut in it, one unit
     * past the tab's right edge so it also cuts the line the sidebar ends with.
     */
    public void setNotchColor(int notchColor) {
        this.notchColor = notchColor;
    }

    /** The icon before the name, as rows of {@code '#'} and {@code '.'}. */
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
        int x = this.getX();
        int y = this.getY();
        boolean hovered = !this.selected && this.isHovered();
        if (this.selected) {
            graphics.fill(x, y, x + this.width, y + this.height, SELECTED_BG);
            graphics.fill(x, y, x + BAR_WIDTH, y + this.height, SELECTED_BAR);
            if (this.notchColor != 0) {
                int right = x + this.width + 1;
                int middle = y + this.height / 2;
                for (int i = 0; i < NOTCH; i++) {
                    graphics.fill(right - (NOTCH - i), middle - i - 1, right, middle - i, this.notchColor);
                    graphics.fill(right - (NOTCH - i), middle + i, right, middle + i + 1, this.notchColor);
                }
            }
        } else if (hovered) {
            graphics.fill(x, y, x + this.width, y + this.height, HOVER_BG);
        }
        int color = this.selected ? SELECTED_TEXT : hovered ? HOVER_TEXT : UNSELECTED_TEXT;

        int textX = x + 8;
        if (icon.length > 0) {
            int iconY = y + (this.height - icon.length) / 2;
            for (int pass = 0; pass < 2; pass++) {
                int offset = pass == 0 ? 1 : 0;
                for (int row = 0; row < icon.length; row++) {
                    for (int column = 0; column < icon[row].length(); column++) {
                        if (icon[row].charAt(column) == '#') {
                            int px = textX + column + offset;
                            int py = iconY + row + offset;
                            graphics.fill(px, py, px + 1, py + 1, pass == 0 ? ICON_SHADOW : color);
                        }
                    }
                }
            }
            textX += icon[0].length() + 5;
        }

        UiText.draw(graphics, net.minecraft.client.Minecraft.getInstance().font, this.getMessage(),
                textX, y + (this.height - 8) / 2, color);
    }

    @Override
    //? if <1.21.9 {
    protected boolean isValidClickButton(int button) {
    //?} else {
    protected boolean isValidClickButton(net.minecraft.client.input.MouseButtonInfo buttonInfo) {
    //?}
        // Only allow left-click
        //? if <1.21.9 {
        return button == InputConstants.MOUSE_BUTTON_LEFT;
        //?} else {
        return buttonInfo.button() == InputConstants.MOUSE_BUTTON_LEFT;
        //?}
    }
}
