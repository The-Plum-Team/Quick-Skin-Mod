package com.quickskin.mod.client.gui.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Gives a menu its own GUI scale, chosen from the window size, so it looks the same whatever GUI
 * Scale the player picked.
 *
 * <p>Only the window's current scale changes, while the menu is shown. The player's GUI Scale
 * option is never written, and nothing goes through {@code resizeDisplay()}, whose framebuffer
 * resize and option change other mods react to. Rendering, the mouse and scissoring all read the
 * window's scale, so they follow the menu's scale together; {@link #restore} puts back the scale
 * the player's options give when the menu goes away.
 *
 * <p>A dialog drawn over a menu ({@link #follow}) takes the menu's scale too, so the menu behind
 * it keeps its size and layout instead of being drawn again at the player's scale.
 */
public final class MenuGuiScale {
    /** The menus are laid out for a GUI canvas of at least about this size... */
    private static final int TARGET_GUI_WIDTH = 520;
    private static final int TARGET_GUI_HEIGHT = 300;
    /** ...and look as intended at this height, the canvas of a 1080p window at scale 3. */
    private static final int REFERENCE_GUI_HEIGHT = 360;
    /** ...and Minecraft allows no scale whose canvas is smaller than this. */
    private static final int MIN_GUI_WIDTH = 320;
    private static final int MIN_GUI_HEIGHT = 240;

    /** Dialogs drawn at a menu's scale, so a dialog opened over one of them follows it too. */
    private static final Set<Screen> FOLLOWERS = Collections.newSetFromMap(new WeakHashMap<>());

    private MenuGuiScale() {
    }

    /** A screen drawn at a menu's own scale, whose dialogs {@link #follow} it. */
    public interface Scaled {
    }

    /**
     * Gives a dialog the menu's scale when the screen it is drawn over has it. Call first in the
     * dialog's {@code init()}, which runs after the menu's {@code removed()} put back the player's
     * scale, and call {@link #leave} from its {@code removed()}.
     *
     * @return whether the dialog follows the menu's scale
     */
    public static boolean follow(Minecraft minecraft, Screen dialog, @Nullable Screen parent) {
        if (!(parent instanceof Scaled) && !FOLLOWERS.contains(parent)) {
            FOLLOWERS.remove(dialog);
            return false;
        }
        FOLLOWERS.add(dialog);
        apply(minecraft);
        dialog.width = minecraft.getWindow().getGuiScaledWidth();
        dialog.height = minecraft.getWindow().getGuiScaledHeight();
        return true;
    }

    /** Puts back the player's scale when a dialog that {@link #follow followed} a menu goes away. */
    public static void leave(Minecraft minecraft, Screen dialog) {
        if (FOLLOWERS.contains(dialog)) {
            restore(minecraft);
        }
    }

    /**
     * Sets the window to the menu's scale for its current size.
     *
     * @return whether the scale changed, which changes the scaled screen size
     */
    public static boolean apply(Minecraft minecraft) {
        var window = minecraft.getWindow();
        int scale = scaleFor(minecraft);
        // getGuiScale() is a double before 1.21.8 and an int from it; both compare and convert
        double current = window.getGuiScale();
        if (current == scale) {
            return false;
        }
        window.setGuiScale(scale);
        return true;
    }

    /** The menu's scale for the window's current size. */
    public static int scaleFor(Minecraft minecraft) {
        var window = minecraft.getWindow();
        return forFramebuffer(window.getWidth(), window.getHeight());
    }

    /** Puts back the scale the player's GUI Scale option gives, as {@code resizeDisplay()} computes it. */
    public static void restore(Minecraft minecraft) {
        var window = minecraft.getWindow();
        int scale = window.calculateScale(minecraft.options.guiScale().get(),
                minecraft.options.forceUnicodeFont().get());
        double current = window.getGuiScale();
        if (current != scale) {
            window.setGuiScale(scale);
        }
    }

    /**
     * The menu's scale for a framebuffer of the given size, in pixels: of the scales that still
     * leave the target canvas, the one whose canvas comes nearest the reference height, so the
     * menu keeps about the proportions it has at 1080p in any window. Taking the largest fitting
     * scale instead would shrink a 4K canvas to 548x308, which draws everything a sixth larger.
     * It is at least 2 wherever Minecraft allows it, so text stays readable in small windows.
     */
    static int forFramebuffer(int width, int height) {
        int fitting = Math.min(width / TARGET_GUI_WIDTH, height / TARGET_GUI_HEIGHT);
        double ideal = Math.min((double) width / TARGET_GUI_WIDTH, (double) height / REFERENCE_GUI_HEIGHT);
        int nearest = (int) Math.floor(ideal);
        // Nearest by ratio: a canvas a fifth too tall is as far off as one a fifth too short
        if (nearest + 1 <= fitting && ideal * ideal > nearest * (nearest + 1.0)) {
            nearest++;
        }
        int largestAllowed = Math.max(1, Math.min(width / MIN_GUI_WIDTH, height / MIN_GUI_HEIGHT));
        return Math.min(largestAllowed, Math.max(2, nearest));
    }
}
