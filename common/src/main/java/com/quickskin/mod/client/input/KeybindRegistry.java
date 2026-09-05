package com.quickskin.mod.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import com.quickskin.mod.platform.QuickSkinInfo;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.registry.client.keymappings.KeyMappingRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
//? if >=1.21.11 {
import net.minecraft.resources.Identifier;
//?}
import org.lwjgl.glfw.GLFW;

/**
 * Keybind registration and handling for QuickSkin
 * Uses Architectury's KeyMappingRegistry for cross-platform compatibility
 */
@Environment(EnvType.CLIENT)
public class KeybindRegistry {

    // Keybind category
    //? if <1.21.11 {
    private static final String CATEGORY = "key.categories." + QuickSkinInfo.MOD_ID;
    //?} else {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "keybinds"));
    //?}

    // Keybind definitions
    public static KeyMapping OPEN_SKIN_MENU;

    /**
     * Registers all keybinds
     * Called from QuickSkinClient.init()
     */
    public static void init() {
        // Create keybind for opening skin menu (default: none)
        OPEN_SKIN_MENU = new KeyMapping(
            "key." + QuickSkinInfo.MOD_ID + ".open_menu",
            InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(),
            CATEGORY
        );

        // Register with Architectury
        KeyMappingRegistry.register(OPEN_SKIN_MENU);

        // Register tick handler to check for key presses
        ClientTickEvent.CLIENT_POST.register(client -> {
            handleKeyPresses(client);
        });
    }

    /**
     * Handles key presses
     * Called every client tick
     */
    private static void handleKeyPresses(Minecraft client) {
        // Check if open menu key was pressed
        while (OPEN_SKIN_MENU.consumeClick()) {
            // Phase 8: Open skin selection screen
            if (client.player != null) {
                //? if <26.2 {
                client.setScreen(new com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen(client.screen));
                //?} else {
                client.gui.setScreen(new com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen(client.gui.screen()));
                //?}
            }
        }
    }
}
