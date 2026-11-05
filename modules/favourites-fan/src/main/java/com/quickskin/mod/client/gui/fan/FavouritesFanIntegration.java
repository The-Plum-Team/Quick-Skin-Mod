package com.quickskin.mod.client.gui.fan;

import com.mojang.blaze3d.platform.InputConstants;
import com.quickskin.mod.client.gui.GuiCompat;
import com.quickskin.mod.client.input.KeybindRegistry;
import dev.architectury.event.CompoundEventResult;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.client.ClientGuiEvent;
import dev.architectury.event.events.client.ClientRawInputEvent;
import dev.architectury.event.events.client.ClientTickEvent;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;

/**
 * Registers the favourites fan: its key, the mouse buttons, wheel and Esc it takes while open, and
 * its HUD layer. Clicks and the wheel go to the fan instead of the game only while it is open in
 * the world, so the click that picks a skin never attacks and the wheel never changes the slot.
 */
@Environment(EnvType.CLIENT)
public final class FavouritesFanIntegration {
    private static boolean initialized;
    private static KeyMapping key;

    private FavouritesFanIntegration() {
    }

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        KeybindRegistry.registerKeys();
        key = KeybindRegistry.FAVOURITES_FAN;

        ClientTickEvent.CLIENT_POST.register(client -> {
            while (key.consumeClick()) {
                FavouritesFan.toggle(client);
            }
            FavouritesFan.tick(client);
        });

        //? if <1.21.9 {
        ClientRawInputEvent.MOUSE_CLICKED_PRE.register((client, button, action, mods) -> onButton(client, button, action));
        //?} else {
        ClientRawInputEvent.MOUSE_CLICKED_PRE.register((client, info, action) -> onButton(client, info.button(), action));
        //?}

        //? if <1.21 {
        ClientRawInputEvent.MOUSE_SCROLLED.register((client, amount) -> onScroll(amount));
        //?} else {
        ClientRawInputEvent.MOUSE_SCROLLED.register((client, amountX, amountY) -> onScroll(amountY));
        //?}

        //? if <1.21.9 {
        ClientRawInputEvent.KEY_PRESSED.register((client, keyCode, scanCode, action, modifiers) -> onKey(keyCode, action));
        //?} else {
        ClientRawInputEvent.KEY_PRESSED.register((client, action, keyEvent) -> onKey(GuiCompat.keyCode(keyEvent), action));
        //?}

        // Esc reaches the fan here only on some loaders (Forge reports keys after the game handled
        // them); elsewhere PauseGameMixin keeps the game from pausing. Should that hook not apply,
        // the pause menu Esc opens is what closes the fan
        ClientGuiEvent.SET_SCREEN.register(screen -> {
            if (screen instanceof PauseScreen && FavouritesFan.capturesMouse() && GuiCompat.currentScreen() == null) {
                FavouritesFan.close();
                return CompoundEventResult.interruptFalse(screen);
            }
            return CompoundEventResult.pass();
        });

        ClientGuiEvent.RENDER_HUD.register((graphics, tickDelta) -> FanRenderer.render(graphics));
    }

    private static EventResult onButton(Minecraft client, int button, int action) {
        if (!FavouritesFan.capturesMouse() || GuiCompat.currentScreen() != null) {
            return EventResult.pass();
        }
        if (action == InputConstants.PRESS) {
            if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                FavouritesFan.click(client);
            } else if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
                FavouritesFan.close();
            }
        }
        return EventResult.interruptTrue();
    }

    private static EventResult onScroll(double amount) {
        if (!FavouritesFan.capturesMouse() || GuiCompat.currentScreen() != null) {
            return EventResult.pass();
        }
        FavouritesFan.scroll(amount);
        return EventResult.interruptTrue();
    }

    private static EventResult onKey(int keyCode, int action) {
        if (FavouritesFan.capturesMouse() && GuiCompat.currentScreen() == null
                && keyCode == InputConstants.KEY_ESCAPE && action == InputConstants.PRESS) {
            FavouritesFan.close();
            return EventResult.interruptTrue();
        }
        return EventResult.pass();
    }

    /** Puts the fan away with the session, as leaving a world does. */
    public static void resetSessionState() {
        FavouritesFan.reset();
        FanRenderer.reset();
    }
}
