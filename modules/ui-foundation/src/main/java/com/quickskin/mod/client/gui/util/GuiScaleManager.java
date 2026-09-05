package com.quickskin.mod.client.gui.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;

public class GuiScaleManager {
    private static Integer originalGuiScale = null;
    private static boolean scaleChanged = false;

    /**
     * Force set the GUI scale for the QuickSkin menus
     * @param targetScale The desired GUI scale (1-4, where 0 = Auto)
     * @return true if a screen resize was triggered, false otherwise.
     */
    public static boolean setMenuGuiScale(int targetScale) {
        try {
            Minecraft mc = Minecraft.getInstance();
            OptionInstance<Integer> guiScaleOption = mc.options.guiScale();

            // Store the original scale if we haven't already
            if (originalGuiScale == null) {
                originalGuiScale = guiScaleOption.get();
            }

            // Only change if different from current
            int currentScale = guiScaleOption.get();
            if (currentScale != targetScale) {
                guiScaleOption.set(targetScale);
                scaleChanged = true;

                // Force window to recalculate scaled dimensions
                // This will cause the screen to reinit, which is why the caller should return immediately
                //? if <26.1.2 {
                mc.resizeDisplay();
                //?} else {
                mc.resizeGui();
                //?}

                return true;
            }
        } catch (Exception e) {
        }
        return false;
    }

    /**
     * Restore the original GUI scale
     */
    public static void restoreOriginalGuiScale() {
        try {
            if (originalGuiScale == null || !scaleChanged) {
                return; // Nothing to restore
            }

            Minecraft mc = Minecraft.getInstance();
            OptionInstance<Integer> guiScaleOption = mc.options.guiScale();
            int currentScale = guiScaleOption.get();

            if (currentScale != originalGuiScale) {
                guiScaleOption.set(originalGuiScale);

                // Force window to recalculate scaled dimensions
                //? if <26.1.2 {
                mc.resizeDisplay();
                //?} else {
                mc.resizeGui();
                //?}
            }

            // Reset tracking variables
            originalGuiScale = null;
            scaleChanged = false;

        } catch (Exception e) {
        }
    }

    /**
     * Get the optimal GUI scale for the QuickSkin menu.
     * Returns 2 for consistent layout.
     */
    public static int getOptimalMenuScale() {
        return 2;
    }

}
