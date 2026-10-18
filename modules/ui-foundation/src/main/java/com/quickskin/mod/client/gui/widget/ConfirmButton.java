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
 * The skin menu's Confirm button, and the buttons of Quick Skin's dialogs, drawn like a Bedrock
 * Edition button in dark-mode greys: a flat
 * charcoal face inside a thin light-grey border and a black outline, on a darker lip that gives it
 * depth. The face lightens
 * under the mouse and sinks onto the lip while pressed; a selected button, such as one whose menu
 * is open, keeps a bright border. Its label is drawn in upper case in Quick Skin's own blocky pixel
 * font with a drop shadow, at the largest whole number of screen pixels per font pixel whose capitals
 * fill at most five eighths of the room inside the border (one to three GUI pixels), so its pixels
 * stay crisp while it grows in steps finer than the GUI scale; a label too long for the face is drawn
 * smaller. {@link #heightForLabel} sizes a button around a chosen label size instead.
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
    // A dangerous action, such as deleting: the same button in reds
    private static final int DANGER_FACE = 0xFFB83030;
    private static final int DANGER_FACE_HOVER = 0xFFD84040;
    private static final int DANGER_BORDER = 0xFFE06060;
    private static final int DANGER_BORDER_HOVER = 0xFFFF9090;
    private static final int DANGER_LIP = 0xFF5A1414;
    /** Height of the darker lip under the face; the face sinks by this much when pressed. */
    private static final int LIP_HEIGHT = 3;
    private static final int MAX_LABEL_SCALE = 3;
    /** Room the label leaves at the face's sides, together. */
    private static final int LABEL_MARGIN = 16;
    /**
     * The room inside the border is at least this many times the capitals' height: they fill five
     * eighths of it, with three sixteenths above and below.
     */
    private static final float ROOM_PER_CAPITALS = 1.6f;
    /** Outline, border and lip around the room inside the border, together. */
    private static final int FRAME_HEIGHT = 4 + LIP_HEIGHT;
    /** Height of the font's capitals, in font pixels. */
    private static final int CAPITALS_HEIGHT = 10;
    /**
     * Distance from the text origin to the middle of the font's capitals, in font pixels. A bitmap
     * glyph's top is drawn 7 - ascent below the origin: with an ascent of 14, the four rows kept
     * for accents start at -7 and the capitals run from -3 to 7, on the default font's baseline.
     */
    private static final float CAPITALS_MIDDLE = 2.0f;

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
    private boolean danger;
    private float labelScale = 1.0f;

    public ConfirmButton(int x, int y, int width, int height, Component label, OnPress onPress) {
        super(x, y, width, height, label, onPress, DEFAULT_NARRATION);
    }

    /** Keeps the border bright, as while the menu this button opens is showing. */
    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    /**
     * Height of the darker lip under the face, which the face sinks onto while pressed. A small
     * button, such as an {@link IconButton}, can stand on a thinner one.
     */
    protected int lipHeight() {
        return LIP_HEIGHT;
    }

    /** Draws the button in reds, for an action such as deleting. A disabled button stays grey. */
    public void setDanger(boolean danger) {
        this.danger = danger;
    }

    public boolean isDanger() {
        return danger;
    }

    /**
     * Draws the label smaller, by this share of its usual size rounded to whole screen pixels per
     * font pixel so it stays crisp, for a button whose label would otherwise nearly touch its
     * border, such as a dialog's.
     */
    public void setLabelScale(float labelScale) {
        this.labelScale = labelScale;
    }

    /** The width at which a button of the given height shows the label at its full size. */
    public static int fittingWidth(net.minecraft.client.gui.Font font, Component label, int height) {
        double guiScale = guiScale();
        int pixels = labelPixels(height - FRAME_HEIGHT, guiScale);
        return (int) Math.ceil(font.width(labelText(label)) * pixels / guiScale) + LABEL_MARGIN;
    }

    /**
     * The height of a button that draws its label at the crisp size nearest to the given number of
     * GUI pixels per font pixel, with the label's usual room above and below. It follows the GUI
     * scale, so the label keeps the same share of the button in every window.
     */
    public static int heightForLabel(float guiPixelsPerFontPixel) {
        double guiScale = guiScale();
        int pixels = Math.max((int) Math.round(guiScale), (int) Math.round(guiScale * guiPixelsPerFontPixel));
        // Rounding up keeps labelPixels() at this size: the room only gets bigger
        int room = (int) Math.ceil(CAPITALS_HEIGHT * ROOM_PER_CAPITALS * pixels / guiScale - 1e-4);
        return room + FRAME_HEIGHT;
    }

    /**
     * The whole number of screen pixels per font pixel whose capitals fill at most 1 /
     * ROOM_PER_CAPITALS of this much room inside the border, between one and MAX_LABEL_SCALE GUI
     * pixels.
     */
    private static int labelPixels(int room, double guiScale) {
        int smallest = (int) Math.round(guiScale);
        int fitting = (int) Math.floor(room * guiScale / (CAPITALS_HEIGHT * ROOM_PER_CAPITALS) + 1e-4);
        return Math.max(smallest, Math.min(MAX_LABEL_SCALE * smallest, fitting));
    }

    private static double guiScale() {
        // getGuiScale() is a double before 1.21.8 and an int from it; both convert
        double scale = Minecraft.getInstance().getWindow().getGuiScale();
        return Math.max(1.0, scale);
    }

    /** Text in the label's pixel font, in upper case, as Quick Skin's dialogs title themselves. */
    public static Component pixelText(Component text) {
        return labelText(text);
    }

    /** Distance from the pixel font's text origin to the middle of its capitals, in font pixels. */
    public static float pixelCapitalsMiddle() {
        return CAPITALS_MIDDLE;
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
        int lip = lipHeight();
        int sink = pressed ? lip : 0;

        // Outline, then the lip along the bottom, then the face resting on it
        graphics.fill(x, y, right, bottom, OUTLINE);
        int innerLeft = x + 1;
        int innerRight = right - 1;
        int innerBottom = bottom - 1;
        int faceTop = y + 1 + sink;
        int faceBottom = innerBottom - lip + sink;
        if (!pressed) {
            graphics.fill(innerLeft, faceBottom, innerRight, innerBottom,
                    !this.active ? LIP_DISABLED : this.danger ? DANGER_LIP : LIP);
        }
        int face;
        int border;
        if (!this.active) {
            face = FACE_DISABLED;
            border = BORDER_DISABLED;
        } else if (this.danger) {
            face = hovered ? DANGER_FACE_HOVER : DANGER_FACE;
            border = hovered ? DANGER_BORDER_HOVER : DANGER_BORDER;
        } else {
            face = hovered ? FACE_HOVER : FACE;
            border = this.selected ? BORDER_SELECTED : hovered ? BORDER_HOVER : BORDER;
        }
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
        double guiScale = guiScale();
        int smallest = (int) Math.round(guiScale);
        int pixels = labelPixels(faceHeight - 2, guiScale);
        if (labelScale < 1.0f) {
            pixels = Math.max(1, Math.round(pixels * labelScale));
            smallest = Math.min(smallest, pixels);
        }
        while (pixels > smallest && textWidth * pixels / guiScale > room) {
            pixels--;
        }
        float crispScale = (float) (pixels / guiScale);
        float scale = textWidth * crispScale > room ? room / (float) textWidth : crispScale;
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
