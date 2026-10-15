package com.quickskin.mod.client.gui.widget;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * A text field in the style of {@link ConfirmButton}: a dark well sunk into the same black outline
 * and thin grey border, shaded along its top edge where a button has its lip along the bottom. The
 * border lightens under the mouse, like a button's, and turns brighter still while the field has
 * the keyboard.
 *
 * <p>The field is a borderless {@link EditBox} covering the text area, which lies inside the well's
 * padding; this widget draws the well around it and moves the text, which a borderless field draws
 * along its top edge, down to the well's middle. Clicks inside the text area place the cursor as
 * vanilla does, since the text still starts at the widget's left edge. {@link FieldIconButton} and
 * {@link FieldLinkButton} draw the same well, so a button beside the field looks part of it.
 */
@Environment(EnvType.CLIENT)
public class SearchField extends EditBox {
    private static final int OUTLINE = 0xFF0E0E0F;
    static final int BORDER = 0xFF6E6F73;
    static final int BORDER_HOVER = 0xFF8D8E93;
    static final int BORDER_FOCUSED = 0xFFC6C7CC;
    static final int BORDER_DISABLED = 0xFF46474A;
    static final int WELL = 0xFF1E1F21;
    /** The well of a button drawn in this style, under the mouse. */
    static final int WELL_HOVER = 0xFF2A2B2E;
    private static final int WELL_SHADE = 0xFF151617;
    /** Depth of the shade along the well's top edge. */
    private static final int SHADE_HEIGHT = 2;
    /**
     * Room between the outside of the field and the text: six pixels inside the well, as much as
     * the well leaves below the text.
     */
    private static final int TEXT_PADDING = 8;
    /** Height of a line of Minecraft's font. */
    private static final int LINE_HEIGHT = 8;

    /** A field whose well fills the given rectangle. */
    public SearchField(Font font, int x, int y, int width, int height, Component narration) {
        super(font, x + TEXT_PADDING, y, width - 2 * TEXT_PADDING, height, narration);
        setBordered(false);
    }

    @Override
    //? if <26.1 {
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //?} else {
    public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    //?}
        if (!isVisible()) {
            return;
        }
        int left = getX() - TEXT_PADDING;
        int right = getX() + this.width + TEXT_PADDING;
        int top = getY();
        int bottom = top + this.height;
        boolean hovered = mouseX >= left && mouseX < right && mouseY >= top && mouseY < bottom;
        int border = isFocused() ? BORDER_FOCUSED : hovered ? BORDER_HOVER : BORDER;

        drawWell(graphics, left, top, right, bottom, border, WELL);

        // The text in the middle of the well below its shade
        int shift = (this.height - LINE_HEIGHT) / 2 + 1;
//? if <1.21.6 {
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, shift, 0.0F);
//?} else {
        graphics.pose().pushMatrix();
        graphics.pose().translate(0.0F, shift);
//?}
//? if <26.1 {
        super.renderWidget(graphics, mouseX, mouseY - shift, partialTick);
//?} else {
        super.extractWidgetRenderState(graphics, mouseX, mouseY - shift, partialTick);
//?}
//? if <1.21.6 {
        graphics.pose().popPose();
//?} else {
        graphics.pose().popMatrix();
//?}
    }

    /** The outline, the border in the given colour, then the well with its shaded top edge. */
    //? if <26.1 {
    static void drawWell(GuiGraphics graphics, int left, int top, int right, int bottom, int border, int well) {
    //?} else {
    static void drawWell(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, int border, int well) {
    //?}
        graphics.fill(left, top, right, bottom, OUTLINE);
        graphics.fill(left + 1, top + 1, right - 1, bottom - 1, border);
        graphics.fill(left + 2, top + 2, right - 2, bottom - 2, well);
        graphics.fill(left + 2, top + 2, right - 2, top + 2 + SHADE_HEIGHT, WELL_SHADE);
    }
}
