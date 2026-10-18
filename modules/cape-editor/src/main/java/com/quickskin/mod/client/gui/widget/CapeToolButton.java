package com.quickskin.mod.client.gui.widget;

import com.quickskin.mod.client.gui.util.UiText;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * A small button of the cape editor in Quick Skin's button style, on a two-pixel lip: an optional
 * {@link CapeEditorStyle} icon and a short label, centred. While {@link #setSelected selected} it is
 * blue, like a selected Settings tab, which marks the chosen resolution and a toggle that is on.
 */
@Environment(EnvType.CLIENT)
public class CapeToolButton extends CapeEditorButton {
    private static final int LIP_HEIGHT = 2;
    private static final int ICON_GAP = 3;
    /** Room left and right of the content, inside the button's edges. */
    public static final int TEXT_MARGIN = 6;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_DISABLED = 0xFF6E6F73;

    @Nullable
    private final String[] icon;
    private Component label;
    private boolean selected;

    public CapeToolButton(int x, int y, int width, int height, @Nullable String[] icon, Component label,
                          Component message, OnPress onPress) {
        super(x, y, width, height, message, onPress);
        this.icon = icon;
        this.label = label;
    }

    /** Width of an icon with a label {@code labelWidth} wide beside it. */
    public static int contentWidth(int labelWidth) {
        return CapeEditorStyle.ICON_SIZE + (labelWidth > 0 ? ICON_GAP + labelWidth : 0);
    }

    /** What the button shows beside its icon; its message stays the full description. */
    public void setLabel(Component label) {
        this.label = label;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public boolean isSelected() {
        return selected;
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
        int face;
        int border;
        int lip;
        if (!this.active) {
            face = 0xFF2B2C2E;
            border = 0xFF46474A;
            lip = 0xFF161617;
        } else if (selected) {
            face = hovered ? 0xFF5A6E8A : 0xFF4E6078;
            border = hovered ? 0xFF9AB6D2 : CapeEditorStyle.ACCENT;
            lip = 0xFF27303C;
        } else {
            face = hovered ? 0xFF4A4B4F : 0xFF3A3B3E;
            border = hovered ? 0xFF8D8E93 : 0xFF6E6F73;
            lip = 0xFF1C1D1F;
        }
        graphics.fill(x, y, right, bottom, CapeEditorStyle.OUTLINE);
        graphics.fill(x + 1, bottom - 1 - LIP_HEIGHT, right - 1, bottom - 1, lip);
        graphics.fill(x + 1, y + 1, right - 1, bottom - 1 - LIP_HEIGHT, border);
        graphics.fill(x + 2, y + 2, right - 2, bottom - 2 - LIP_HEIGHT, face);

        Font font = Minecraft.getInstance().font;
        int color = this.active ? TEXT : TEXT_DISABLED;
        int faceMiddle = y + (this.height - LIP_HEIGHT) / 2;
        String text = label == null ? "" : label.getString();
        int room = this.width - TEXT_MARGIN;
        int iconWidth = icon == null ? 0 : CapeEditorStyle.ICON_SIZE;
        int gap = icon != null && !text.isEmpty() ? ICON_GAP : 0;
        // A label that does not fit beside the icon gives way to the icon alone
        if (font.width(text) + iconWidth + gap > room) {
            if (icon != null) {
                text = "";
                gap = 0;
            } else {
                text = font.plainSubstrByWidth(text, Math.max(0, room - font.width("..."))) + "...";
            }
        }
        int contentWidth = iconWidth + gap + font.width(text);
        int contentX = x + (this.width - contentWidth) / 2;
        if (icon != null) {
            CapeEditorStyle.icon(graphics, icon, contentX, faceMiddle - 3, color);
        }
        if (!text.isEmpty()) {
            UiText.draw(graphics, font, text, contentX + iconWidth + gap, faceMiddle - 4, color);
        }
    }
}
