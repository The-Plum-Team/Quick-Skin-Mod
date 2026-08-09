package com.quickskin.mod.client.gui.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Guards the Minecraft 1.21.1 screen-rendering seam.
 *
 * <p>In this version, {@link Screen#render} invokes {@code renderBackground} before rendering its
 * widgets. Quick Skin screens draw their own panels before calling {@code super.render}, so each
 * one must suppress that inherited background pass or Vanilla will process the custom UI while
 * leaving the subsequently rendered buttons untouched.</p>
 */
class ScreenBackgroundOwnershipTest {
    private static final List<Class<? extends Screen>> CUSTOM_BACKGROUND_SCREENS = List.of(
            SettingsScreen.class,
            DeletionConfirmScreen.class,
            RenameScreen.class,
            UploadToMojangScreen.class,
            CapeAdjustScreen.class,
            PlayerSkinMenuScreen.class,
            PlayerCapeMenuScreen.class
    );

    @Test
    void customScreensDeclareThe1211BackgroundHook() throws NoSuchMethodException {
        for (Class<? extends Screen> screenClass : CUSTOM_BACKGROUND_SCREENS) {
            Method backgroundHook = screenClass.getMethod(
                    "renderBackground",
                    GuiGraphics.class,
                    int.class,
                    int.class,
                    float.class
            );

            assertSame(
                    screenClass,
                    backgroundHook.getDeclaringClass(),
                    () -> screenClass.getSimpleName() + " must own the Minecraft 1.21.1 background pass"
            );
        }
    }
}
