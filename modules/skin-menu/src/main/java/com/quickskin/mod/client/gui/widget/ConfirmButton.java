package com.quickskin.mod.client.gui.widget;

import com.mojang.blaze3d.platform.InputConstants;
import com.quickskin.mod.platform.QuickSkinInfo;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}

import java.util.Locale;

/**
 * The skin menu's Confirm button, drawn like a Bedrock Edition button in dark-mode greys: a flat
 * charcoal face inside a thin light-grey border and a black outline, on a darker lip that gives it
 * depth. The face lightens
 * under the mouse and sinks onto the lip while pressed; a selected button, such as one whose menu
 * is open, keeps a bright border. Its label is drawn in upper case in Quick Skin's own blocky pixel
 * font with a drop shadow, at the largest whole multiple of the GUI size whose capitals fill at most
 * seven tenths of the face (three at most), so its pixels stay crisp; a label too long for the face
 * is drawn smaller.
 */
@Environment(EnvType.CLIENT)
public class ConfirmButton extends Button {
    // Charcoal face inside a thin light-grey border, within a black outline
    private static final int OUTLINE = 0xFF0E0E0F;
    private static final int FACE = 0xFF3A3B3E;
    private static final int FACE_HOVER = 0xFF4A4B4F;
    private static final int FACE_DISABLED = 0xFF2B2C2E;
    private static final int BORDER = 0xFF6E6F73;
    private static final int BORDER_HOVER = 0xFF8D8E93;
    private static final int BORDER_DISABLED = 0xFF46474A;
    private static final int BORDER_SELECTED = 0xFFC6C7CC;
    private static final int LIP = 0xFF1C1D1F;
    private static final int LIP_DISABLED = 0xFF161617;
    /** Height of the darker lip under the face; the face sinks by this much when pressed. */
    private static final int LIP_HEIGHT = 3;
    private static final int MAX_LABEL_SCALE = 3;
    /** Room the label leaves at the face's sides, together. */
    private static final int LABEL_MARGIN = 16;
    /** The capitals fill at most this share of the face's height. */
    private static final float MAX_CAPITALS_SHARE = 0.7f;
    /** Height of the font's capitals, in font pixels. */
    private static final int CAPITALS_HEIGHT = 9;
    /**
     * Distance from the text origin to the middle of the font's capitals, in font pixels. A bitmap
     * glyph's top is drawn 7 - ascent below the origin: with an ascent of 12, the three rows kept
     * for accents start at -5 and the capitals run from -2 to 7, on the default font's baseline.
     */
    private static final float CAPITALS_MIDDLE = 2.5f;

    /**
     * The label's font: Quick Skin's own blocky capitals, digits and accented Latin letters, from
     * assets/quickskin/font/confirm.json. Characters it lacks fall back to Minecraft's default font.
     */
//? if <1.21 {
    private static final ResourceLocation FONT = new ResourceLocation(QuickSkinInfo.MOD_ID, "confirm");
//?} else if <1.21.9 {
    private static final ResourceLocation FONT = ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "confirm");
//?} else if <1.21.11 {
    private static final net.minecraft.network.chat.FontDescription FONT = new net.minecraft.network.chat.FontDescription.Resource(
            ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "confirm"));
//?} else {
    private static final net.minecraft.network.chat.FontDescription FONT = new net.minecraft.network.chat.FontDescription.Resource(
            Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "confirm"));
//?}

    private boolean selected;

    public ConfirmButton(int x, int y, int width, int height, Component label, OnPress onPress) {
        super(x, y, width, height, label, onPress, DEFAULT_NARRATION);
    }

    /** Keeps the border bright, as while the menu this button opens is showing. */
    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    /** The width at which a button of the given height shows the label at its full size. */
    public static int fittingWidth(net.minecraft.client.gui.Font font, Component label, int height) {
        return font.width(labelText(label)) * labelScale(height - 2 - LIP_HEIGHT) + LABEL_MARGIN;
    }

    /** The whole scale whose capitals fill at most MAX_CAPITALS_SHARE of a face this tall. */
    private static int labelScale(int faceHeight) {
        return Math.max(1, Math.min(MAX_LABEL_SCALE, (int) (faceHeight * MAX_CAPITALS_SHARE / CAPITALS_HEIGHT)));
    }

    private static Component labelText(Component label) {
        return Component.literal(label.getString().toUpperCase(Locale.ROOT)).withStyle(style -> style.withFont(FONT));
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
        int right = x + this.width;
        int bottom = y + this.height;
        boolean hovered = this.active && this.isHovered();
        boolean pressed = hovered && isLeftMouseDown();
        int sink = pressed ? LIP_HEIGHT : 0;

        // Outline, then the lip along the bottom, then the face resting on it
        graphics.fill(x, y, right, bottom, OUTLINE);
        int innerLeft = x + 1;
        int innerRight = right - 1;
        int innerBottom = bottom - 1;
        int faceTop = y + 1 + sink;
        int faceBottom = innerBottom - LIP_HEIGHT + sink;
        if (!pressed) {
            graphics.fill(innerLeft, faceBottom, innerRight, innerBottom, this.active ? LIP : LIP_DISABLED);
        }
        int face = !this.active ? FACE_DISABLED : hovered ? FACE_HOVER : FACE;
        int border = !this.active ? BORDER_DISABLED : this.selected ? BORDER_SELECTED
                : hovered ? BORDER_HOVER : BORDER;
        // A thin border all round the face, then the face inside it
        graphics.fill(innerLeft, faceTop, innerRight, faceBottom, border);
        graphics.fill(innerLeft + 1, faceTop + 1, innerRight - 1, faceBottom - 1, face);

        renderFaceContents(graphics, faceTop, faceBottom);
    }

    /** Draws what the face shows between its top and bottom edges: the label, by default. */
    //? if <26.1 {
    protected void renderFaceContents(GuiGraphics graphics, int faceTop, int faceBottom) {
    //?} else {
    protected void renderFaceContents(GuiGraphicsExtractor graphics, int faceTop, int faceBottom) {
    //?}
        Minecraft minecraft = Minecraft.getInstance();
        Component text = labelText(this.getMessage());
        int textWidth = Math.max(1, minecraft.font.width(text));
        int faceHeight = faceBottom - faceTop;
        int room = this.width - LABEL_MARGIN;
        int wholeScale = labelScale(faceHeight);
        while (wholeScale > 1 && textWidth * wholeScale > room) {
            wholeScale--;
        }
        float scale = textWidth * wholeScale > room ? room / (float) textWidth : wholeScale;
        // Centre the capitals themselves on the face, above the lip, on whole pixels; the eye
        // ignores the shadow
        float textX = Math.round(this.getX() + (this.width - textWidth * scale) / 2.0f);
        float textY = Math.round(faceTop + faceHeight / 2.0f - CAPITALS_MIDDLE * scale);
        int color = this.active ? 0xFFFFFFFF : 0xFFA8A8A8;
//? if <1.21.6 {
        graphics.pose().pushPose();
        graphics.pose().translate(textX, textY, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
//?} else {
        graphics.pose().pushMatrix();
        graphics.pose().translate(textX, textY);
        graphics.pose().scale(scale, scale);
//?}
//? if <26.1 {
        graphics.drawString(minecraft.font, text, 0, 0, color, true);
//?} else {
        graphics.text(minecraft.font, text, 0, 0, color, true);
//?}
//? if <1.21.6 {
        graphics.pose().popPose();
//?} else {
        graphics.pose().popMatrix();
//?}
    }

    private static boolean isLeftMouseDown() {
        return Minecraft.getInstance().mouseHandler.isLeftPressed();
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
