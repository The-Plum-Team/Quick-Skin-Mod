package com.quickskin.mod.client.gui.widget;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.network.chat.Component;

import java.util.function.IntSupplier;

/** A colour square that opens a colour picker; dimmed while inactive. */
@Environment(EnvType.CLIENT)
public class CapeSwatchButton extends CapeEditorButton {
    private static final int BORDER = 0xFF8D8E93;
    private static final int BORDER_HOVER = 0xFFFFFFFF;
    private static final int BORDER_DISABLED = 0xFF46474A;
    private static final int DISABLED_VEIL = 0xB0161617;

    private final IntSupplier argb;

    public CapeSwatchButton(int x, int y, int size, IntSupplier argb, Component message, OnPress onPress) {
        super(x, y, size, size, message, onPress);
        this.argb = argb;
    }

    @Override
    //? if <26.1 {
    protected void draw(GuiGraphics graphics, boolean hovered) {
    //?} else {
    protected void draw(GuiGraphicsExtractor graphics, boolean hovered) {
    //?}
        int x = getX();
        int y = getY();
        int right = x + this.width;
        int bottom = y + this.height;
        graphics.fill(x, y, right, bottom, CapeEditorStyle.OUTLINE);
        graphics.fill(x + 1, y + 1, right - 1, bottom - 1,
                !this.active ? BORDER_DISABLED : hovered ? BORDER_HOVER : BORDER);
        graphics.fill(x + 2, y + 2, right - 2, bottom - 2, argb.getAsInt());
        if (!this.active) {
            graphics.fill(x + 2, y + 2, right - 2, bottom - 2, DISABLED_VEIL);
        }
    }
}
