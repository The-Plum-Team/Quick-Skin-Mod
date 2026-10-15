package com.quickskin.mod.client.gui.widget;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * A dark speech bubble behind a button's choices, its pointer on the right edge aimed at the button
 * that opened it. It only draws: the choices are buttons of their own, laid over it, and it is
 * inactive so that clicks on its padding fall through.
 */
@Environment(EnvType.CLIENT)
public class ChoiceBubble extends AbstractWidget {
    /** How far the pointer reaches beyond the bubble's right edge. */
    public static final int POINTER_LENGTH = 3;
    private static final int OUTLINE = 0xFF0E0E0F;
    private static final int BODY = 0xFF242527;

    private final int pointerY;

    /**
     * A bubble filling the rectangle but for its pointer, which takes the rectangle's last
     * {@link #POINTER_LENGTH} columns and is centred on the row {@code pointerY}.
     */
    public ChoiceBubble(int x, int y, int width, int height, int pointerY) {
        super(x, y, width, height, Component.empty());
        this.pointerY = pointerY;
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
        int right = left + this.width - POINTER_LENGTH;
        int bottom = top + this.height;
        graphics.fill(left, top, right, bottom, OUTLINE);
        graphics.fill(left + 1, top + 1, right - 1, bottom - 1, BODY);
        // The pointer narrows by a row at each end per column, opening through the outline
        int middle = pointerY;
        for (int column = 0; column < POINTER_LENGTH; column++) {
            int x = right - 1 + column;
            graphics.fill(x, middle - 3 + column, x + 1, middle + 4 - column, BODY);
            graphics.fill(x, middle - 4 + column, x + 1, middle - 3 + column, OUTLINE);
            graphics.fill(x, middle + 4 - column, x + 1, middle + 5 - column, OUTLINE);
        }
        int tip = right - 1 + POINTER_LENGTH;
        graphics.fill(tip, middle - 1, tip + 1, middle + 2, OUTLINE);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        // Nothing to narrate: the choices narrate themselves
    }
}
