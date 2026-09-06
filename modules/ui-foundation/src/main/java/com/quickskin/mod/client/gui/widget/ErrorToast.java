package com.quickskin.mod.client.gui.widget;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.network.chat.Component;

/**
 * Simple error toast notification
 * Appears at the top of screen and fades out
 */
@Environment(EnvType.CLIENT)
public class ErrorToast {

    private final Minecraft mc;
    private final Component message;
    private final long creationTime;

    private static final long DISPLAY_DURATION = 3000; // 3 seconds
    private static final int TOAST_WIDTH = 300;
    private static final int TOAST_HEIGHT = 40;
    private static final int TOAST_PADDING = 10;

    public ErrorToast(Component message) {
        this.mc = Minecraft.getInstance();
        this.message = message;
        this.creationTime = System.currentTimeMillis();
    }

    /**
     * Render the toast
     * @return true if still visible, false if expired
     */
    //? if <26.1 {
    public boolean render(GuiGraphics guiGraphics, int screenWidth) {
    //?} else {
    public boolean render(GuiGraphicsExtractor guiGraphics, int screenWidth) {
    //?}
        long elapsed = System.currentTimeMillis() - creationTime;

        if (elapsed > DISPLAY_DURATION) {
            return false; // Expired
        }

        // Calculate alpha for fade in/out
        float alpha = 1.0f;
        if (elapsed < 300) {
            // Fade in
            alpha = elapsed / 300.0f;
        } else if (elapsed > DISPLAY_DURATION - 500) {
            // Fade out
            alpha = (DISPLAY_DURATION - elapsed) / 500.0f;
        }

        alpha = Math.max(0, Math.min(1, alpha));

        // Position at top center
        int x = (screenWidth - TOAST_WIDTH) / 2;
        int y = 20;

        // Draw background with alpha
        int bgColor = (int)(alpha * 255) << 24 | 0xCC0000; // Red background
        guiGraphics.fill(x, y, x + TOAST_WIDTH, y + TOAST_HEIGHT, bgColor);

        // Draw border
        int borderColor = (int)(alpha * 255) << 24 | 0xFF0000;
        guiGraphics.fill(x, y, x + TOAST_WIDTH, y + 1, borderColor);
        guiGraphics.fill(x, y + TOAST_HEIGHT - 1, x + TOAST_WIDTH, y + TOAST_HEIGHT, borderColor);
        guiGraphics.fill(x, y, x + 1, y + TOAST_HEIGHT, borderColor);
        guiGraphics.fill(x + TOAST_WIDTH - 1, y, x + TOAST_WIDTH, y + TOAST_HEIGHT, borderColor);

        // Draw message
        int textColor = (int)(alpha * 255) << 24 | 0xFFFFFF;
        int messageX = x + TOAST_PADDING;
        int messageY = y + (TOAST_HEIGHT / 2) - (mc.font.lineHeight / 2);

        // Truncate message if too long
        String text = message.getString();
        int maxTextWidth = TOAST_WIDTH - (TOAST_PADDING * 2);
        if (mc.font.width(text) > maxTextWidth) {
            text = mc.font.plainSubstrByWidth(text, maxTextWidth - mc.font.width("...")) + "...";
        }

        //? if <26.1 {
        guiGraphics.drawString(mc.font, text, messageX, messageY, textColor, false);
        //?} else {
        guiGraphics.text(mc.font, text, messageX, messageY, textColor, false);
        //?}

        return true; // Still visible
    }
}
