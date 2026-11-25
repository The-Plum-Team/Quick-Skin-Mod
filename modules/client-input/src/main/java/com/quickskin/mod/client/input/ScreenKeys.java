package com.quickskin.mod.client.input;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * Over which screens Quick Skin's keys work. Minecraft hands a key to its key mappings only while
 * no screen is open, so on a screen Quick Skin reads the key itself, before the screen does.
 *
 * <p>Only the title screen and the pause menu take them. Elsewhere a letter is too often typed:
 * chat, a world's name, a search box or another mod's field that is no {@link EditBox}, and Quick
 * Skin's own screens, where the key must not open a menu over itself. Even there a focused text
 * field keeps the key, since another mod may add one.</p>
 */
@Environment(EnvType.CLIENT)
public final class ScreenKeys {
    /** How deep a chain of focused containers is followed; vanilla nests at most a few. */
    private static final int MAX_FOCUS_DEPTH = 16;

    private ScreenKeys() {
    }

    /** Whether the skin menu's key opens the menu over this screen. */
    public static boolean opensSkinMenuOver(Screen screen) {
        return (screen instanceof TitleScreen || screen instanceof PauseScreen) && !typingIn(screen);
    }

    /** Whether the favourites key opens the fan from this screen: only the pause menu of a world. */
    public static boolean opensFavouritesOver(Screen screen) {
        return screen instanceof PauseScreen && !typingIn(screen);
    }

    /** Whether the focused element, followed through nested containers, takes typed text. */
    static boolean typingIn(ContainerEventHandler screen) {
        GuiEventListener focused = screen.getFocused();
        for (int depth = 0; focused != null && depth < MAX_FOCUS_DEPTH; depth++) {
            if (takesText(focused)) {
                return true;
            }
            if (!(focused instanceof ContainerEventHandler container)) {
                return false;
            }
            focused = container.getFocused();
        }
        return false;
    }

    private static boolean takesText(GuiEventListener focused) {
        return focused instanceof EditBox || focused instanceof MultiLineEditBox;
    }
}
