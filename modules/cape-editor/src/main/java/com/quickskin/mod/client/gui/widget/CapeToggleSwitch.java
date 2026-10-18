package com.quickskin.mod.client.gui.widget;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;

/** An on/off switch: a green track with the knob on the right while on, grey with it on the left. */
@Environment(EnvType.CLIENT)
public class CapeToggleSwitch extends CapeEditorButton {
    private static final int ON = 0xFF3C8A3C;
    private static final int ON_HOVER = 0xFF48A048;
    private static final int OFF = 0xFF46474A;
    private static final int OFF_HOVER = 0xFF55565B;
    private static final int KNOB = 0xFFC6C7CC;
    private static final int KNOB_HOVER = 0xFFFFFFFF;

    private final BooleanSupplier on;

    public CapeToggleSwitch(int x, int y, int width, int height, BooleanSupplier on, Component message,
                            OnPress onPress) {
        super(x, y, width, height, message, onPress);
        this.on = on;
    }

    @Override
    //? if <26.1 {
    protected void draw(GuiGraphics graphics, boolean hovered) {
    //?} else {
    protected void draw(GuiGraphicsExtractor graphics, boolean hovered) {
    //?}
        boolean isOn = on.getAsBoolean();
        int x = getX();
        int y = getY();
        int right = x + this.width;
        int bottom = y + this.height;
        graphics.fill(x, y + 2, right, bottom - 2, CapeEditorStyle.OUTLINE);
        graphics.fill(x + 1, y + 3, right - 1, bottom - 3,
                isOn ? (hovered ? ON_HOVER : ON) : (hovered ? OFF_HOVER : OFF));
        int knobWidth = this.height - 4;
        int knobX = isOn ? right - knobWidth : x;
        graphics.fill(knobX, y, knobX + knobWidth, bottom, CapeEditorStyle.OUTLINE);
        graphics.fill(knobX + 1, y + 1, knobX + knobWidth - 1, bottom - 1, hovered ? KNOB_HOVER : KNOB);
    }
}
