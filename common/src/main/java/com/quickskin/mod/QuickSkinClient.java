package com.quickskin.mod;

import com.quickskin.mod.client.input.KeybindRegistry;
import com.quickskin.mod.client.services.*;
import com.quickskin.mod.event.ClientEvents;
import com.quickskin.mod.networking.NetworkTransport;
import com.quickskin.mod.platform.PlatformHelper;
import com.quickskin.mod.runtime.ClientRuntime;
import com.quickskin.mod.runtime.ClientFeatureBindings;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Client-only entry point for QuickSkin mod
 * This class and all its methods are stripped on dedicated servers
 */
@Environment(EnvType.CLIENT)
public class QuickSkinClient {
    private static final ClientRuntime RUNTIME = ClientRuntime.getInstance();
    private static boolean initialized;

    /**
     * Client initialization - only runs on client side
     * Called from platform-specific client entry points
     */
    public static synchronized void init() {
        if (initialized) {
            return;
        }

        ClientFeatureBindings.install();

        // Stores and configuration must be ready before event registration starts async work.
        RUNTIME.initializeStores(PlatformHelper.getConfigDirectory());

        // Initialize client services.
        ModelService.init();
        SkinService.init();
        CapeService.init();
        PlayerAppearanceService.init();
        MojangApiService.init();
        CooldownService.getInstance();

        // Register client networking (S2C receivers).
        NetworkTransport.INSTANCE.initClient();

        // Register client events and keybinds after all their dependencies are available.
        ClientEvents.init(RUNTIME);
        KeybindRegistry.init(() -> com.quickskin.mod.client.gui.GuiCompat.openScreen(
                new com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen(
                        com.quickskin.mod.client.gui.GuiCompat.currentScreen())));

        // Auto-select player's own skin if no skin is currently selected.
        ClientEvents.autoSelectPlayerOwnSkin();
        initialized = true;
    }

    /** Explicit cleanup hook for client platforms that expose one. */
    public static synchronized void close() {
        ClientEvents.close();
        RUNTIME.close();
    }
}
