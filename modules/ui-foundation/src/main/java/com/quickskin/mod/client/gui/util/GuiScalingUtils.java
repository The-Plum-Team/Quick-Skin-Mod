package com.quickskin.mod.client.gui.util;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

/**
 * Utility class for handling GUI scaling and positioning across different resolutions
 * and GUI scale settings.
 */
@Environment(EnvType.CLIENT)
public class GuiScalingUtils {

    /**
     * Get the current GUI scale factor
     */
    public static int getGuiScale() {
        Minecraft mc = Minecraft.getInstance();
        return (int)mc.getWindow().getGuiScale();
    }

    /**
     * Calculate a scale multiplier based on screen size and GUI scale
     */
    public static float getScaleMultiplier(int screenWidth, int screenHeight) {
        // Use 854x480 as the reference resolution (480p widescreen)
        float widthScale = screenWidth / 854f;
        float heightScale = screenHeight / 480f;
        float baseScale = Math.min(widthScale, heightScale);

        // Adjust based on GUI scale setting
        int guiScale = getGuiScale();
        float multiplier = switch (guiScale) {
            case 1 -> 0.75f; // Small
            case 2 -> 1.0f; // Normal
            case 3 -> 1.25f; // Large
            default -> 1.5f; // Auto or larger
        };

        return Mth.clamp(baseScale * multiplier, 0.5f, 2.0f);
    }

    /**
     * Scale a value based on current screen size and GUI scale
     */
    public static int scaleValue(int value, int screenWidth, int screenHeight) {
        return Math.round(value * getScaleMultiplier(screenWidth, screenHeight));
    }

    /**
     * Check if screen is considered "small" (mobile-like or very low resolution)
     */
    public static boolean isSmallScreen(int screenWidth, int screenHeight) {
        return screenWidth < 800 || screenHeight < 600;
    }

    /**
     * Check if screen is considered "large" (4K or higher)
     */
    public static boolean isLargeScreen(int screenWidth, int screenHeight) {
        return screenWidth >= 3840 || screenHeight >= 2160;
    }
}
