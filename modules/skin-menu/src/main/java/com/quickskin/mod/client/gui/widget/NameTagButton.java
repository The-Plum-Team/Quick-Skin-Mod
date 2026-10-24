package com.quickskin.mod.client.gui.widget;

import com.quickskin.mod.client.gui.util.PixelIcon;
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
 * The player's name above the model in the skin menu, drawn like a name tag in the world, and a
 * button: under the mouse the tag darkens and a pencil appears beside it, and a click opens the
 * nickname dialog. Away from the mouse it looks exactly like the plain name tag. The pencil's room
 * is always part of the button, so its bounds never move under the mouse.
 */
@Environment(EnvType.CLIENT)
public class NameTagButton extends Button {
    /** The name is drawn this much larger than GUI text. */
    public static final float SCALE = 1.5f;
    private static final int TAG = 0x40000000;
    private static final int TAG_LIT = 0x80000000;
    private static final int TEXT = 0xFFFFFFFF;
    /** The pencil, drawn at the name's size so its pixels match the letters'. */
    private static final PixelIcon PENCIL = PixelIcon.PENCIL_COMPACT;
    private static final int PENCIL_GAP = 4;
    /** How far right of the tag the pencil, and so the button, reaches. */
    public static final int PENCIL_ROOM = PENCIL_GAP + (int) Math.ceil(PENCIL.width() * SCALE);

    private String name = "";
    private int centerX;
    private float top;
    private int textWidth;

    public NameTagButton(OnPress onPress) {
        super(0, 0, 0, 0, Component.empty(), onPress, DEFAULT_NARRATION);
    }

    /** Shows {@code name} centred on {@code centerX}, with the tag's top at {@code top}. */
    public void place(Font font, String name, int centerX, float top) {
        if (!name.equals(this.name)) {
            this.name = name;
            setMessage(Component.literal(name));
        }
        this.centerX = centerX;
        this.top = top;
        this.textWidth = font.width(name);
        int left = (int) Math.floor(centerX + SCALE * (-(textWidth / 2) - 2));
        setX(left);
        setY((int) Math.floor(top));
        this.width = pencilX() - PENCIL_GAP + PENCIL_ROOM - left;
        this.height = (int) Math.ceil(top + 10 * SCALE) - getY();
    }

    /** Where the pencil starts, a little right of the tag. */
    private int pencilX() {
        return (int) Math.ceil(centerX + SCALE * ((textWidth + 1) / 2 + 2)) + PENCIL_GAP;
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
        Font font = Minecraft.getInstance().font;
        boolean lit = this.active && this.isHoveredOrFocused();
//? if <1.21.6 {
        graphics.pose().pushPose();
        graphics.pose().translate(centerX, top, 0.0F);
        graphics.pose().scale(SCALE, SCALE, 1.0F);
//?} else {
        graphics.pose().pushMatrix();
        graphics.pose().translate(centerX, top);
        graphics.pose().scale(SCALE, SCALE);
//?}
        graphics.fill(-textWidth / 2 - 2, 0, (textWidth + 1) / 2 + 2, 10, lit ? TAG_LIT : TAG);
//? if <26.1 {
        graphics.drawString(font, name, -textWidth / 2, 1, TEXT, false);
//?} else {
        graphics.text(font, name, -textWidth / 2, 1, TEXT, false);
//?}
//? if <1.21.6 {
        graphics.pose().popPose();
//?} else {
        graphics.pose().popMatrix();
//?}
        if (lit) {
            // Centred on the tag, which is as tall as the pencil at this scale or taller
            float pencilTop = top + (10 - PENCIL.height()) * SCALE / 2;
//? if <1.21.6 {
            graphics.pose().pushPose();
            graphics.pose().translate(pencilX(), pencilTop, 0.0F);
            graphics.pose().scale(SCALE, SCALE, 1.0F);
//?} else {
            graphics.pose().pushMatrix();
            graphics.pose().translate(pencilX(), pencilTop);
            graphics.pose().scale(SCALE, SCALE);
//?}
            PENCIL.draw(graphics, 0, 0);
//? if <1.21.6 {
            graphics.pose().popPose();
//?} else {
            graphics.pose().popMatrix();
//?}
        }
    }
}
