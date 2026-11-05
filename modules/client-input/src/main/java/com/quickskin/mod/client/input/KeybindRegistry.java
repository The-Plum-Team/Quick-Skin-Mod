package com.quickskin.mod.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import com.quickskin.mod.platform.QuickSkinInfo;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.registry.client.keymappings.KeyMappingRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
//? if >=1.21.9 && <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else if >=1.21.11 {
import net.minecraft.resources.Identifier;
//?}

/**
 * Keybind registration and handling for QuickSkin
 * Uses Architectury's KeyMappingRegistry for cross-platform compatibility
 */
@Environment(EnvType.CLIENT)
public class KeybindRegistry {

    // Keybind category
    //? if <1.21.9 {
    private static final String CATEGORY = "key.categories." + QuickSkinInfo.MOD_ID;
    //?} else if <1.21.11 {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "keybinds"));
    //?} else {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "keybinds"));
    //?}

    // Keybind definitions
    public static KeyMapping OPEN_SKIN_MENU;
    /** Opens the favourites fan; see the favourites-fan module. */
    public static KeyMapping FAVOURITES_FAN;

    /**
     * Registers Quick Skin's keys, once. Forge and NeoForge call this while the mod is built,
     * before Minecraft registers the controls and reads their saved bindings; a key registered
     * later still works but forgets its binding at every restart. Fabric registers them from
     * {@link #init}.
     */
    public static synchronized void registerKeys() {
        if (OPEN_SKIN_MENU != null) return;
        OPEN_SKIN_MENU = register("open_menu");
        FAVOURITES_FAN = register("favourites_fan");
    }

    /** A Quick Skin key, unbound until the player binds it, in Quick Skin's category of the controls. */
    private static KeyMapping register(String name) {
        KeyMapping key = new KeyMapping(
            "key." + QuickSkinInfo.MOD_ID + "." + name,
            //? if <26.3 {
            InputConstants.Type.KEYSYM,
            //?} else {
            InputConstants.Type.KEYBOARD,
            //?}
            InputConstants.UNKNOWN.getValue(),
            CATEGORY
        );
        KeyMappingRegistry.register(key);
        return key;
    }

    /**
     * Registers all keybinds
     * Called from QuickSkinClient.init()
     */
    public static void init(Runnable openSkinMenu) {
        java.util.Objects.requireNonNull(openSkinMenu, "openSkinMenu");
        registerKeys();

        // Register tick handler to check for key presses
        ClientTickEvent.CLIENT_POST.register(client -> {
            handleKeyPresses(client, openSkinMenu);
        });
    }

    /**
     * Handles key presses
     * Called every client tick
     */
    private static void handleKeyPresses(Minecraft client, Runnable openSkinMenu) {
        // Check if open menu key was pressed
        while (OPEN_SKIN_MENU.consumeClick()) {
            // Phase 8: Open skin selection screen
            if (client.player != null) {
                openSkinMenu.run();
            }
        }
    }
}
