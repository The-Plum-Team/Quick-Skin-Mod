package com.quickskin.mod.client.gui.widget;

//? if <1.21.6 {
import com.mojang.math.Axis;
//?}
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}

/**
 * Moves, scales and turns what is drawn next in the GUI, about a point: a pose stack before
 * 1.21.6 and a 2D matrix stack from then on. Every {@link #push} needs its {@link #pop}.
 */
final class GuiTransform {
    private GuiTransform() {
    }

    /** Draws what follows offset by {@code (dx, dy)}, scaled and turned about {@code (cx, cy)}. */
    static void push(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            float cx, float cy, float dx, float dy, float scale, float degrees) {
        //? if <1.21.6 {
        graphics.pose().pushPose();
        graphics.pose().translate(cx + dx, cy + dy, 0);
        if (degrees != 0) {
            graphics.pose().mulPose(Axis.ZP.rotationDegrees(degrees));
        }
        graphics.pose().scale(scale, scale, 1);
        graphics.pose().translate(-cx, -cy, 0);
        //?} else {
        graphics.pose().pushMatrix();
        graphics.pose().translate(cx + dx, cy + dy);
        if (degrees != 0) {
            graphics.pose().rotate((float) Math.toRadians(degrees));
        }
        graphics.pose().scale(scale, scale);
        graphics.pose().translate(-cx, -cy);
        //?}
    }

    static void pop(
            //? if <26.1 {
            GuiGraphics graphics
            //?} else {
            GuiGraphicsExtractor graphics
            //?}
    ) {
        //? if <1.21.6 {
        graphics.pose().popPose();
        //?} else {
        graphics.pose().popMatrix();
        //?}
    }
}
