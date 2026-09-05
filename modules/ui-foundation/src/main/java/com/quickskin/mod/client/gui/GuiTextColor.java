package com.quickskin.mod.client.gui;

/**
 * Color helpers for text submitted through Minecraft's GUI renderers.
 *
 * <p>{@code GuiGraphics} requires an explicit alpha channel from Minecraft 1.21.6 onward. Keep
 * 24-bit RGB values in APIs such as {@code Style.withColor}; normalize only colors that are passed
 * to a GUI text draw call.</p>
 */
public final class GuiTextColor {
    private static final int OPAQUE_ALPHA = 0xFF000000;
    private static final int RGB_MASK = 0x00FFFFFF;

    private GuiTextColor() {
    }

    public static int opaqueRgb(int rgb) {
        return OPAQUE_ALPHA | (rgb & RGB_MASK);
    }
}
