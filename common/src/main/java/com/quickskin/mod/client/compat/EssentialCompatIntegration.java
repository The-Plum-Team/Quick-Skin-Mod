package com.quickskin.mod.client.compat;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.platform.PlatformHelper;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;

import java.util.UUID;

/**
 * Compatibility integration for Essential mod.
 *
 * Essential includes its own player model rendering on the title screen and pause menu.
 * When both QuickSkin and Essential are installed, this integration:
 * 1. Hides QuickSkin's PlayerWidget, rotate button, and animation buttons from vanilla menus
 * 2. Positions the "Change Skin" button beside Essential's right-hand action rail
 */
@Environment(EnvType.CLIENT)
public class EssentialCompatIntegration {
    private static boolean MOD_AVAILABLE = false;
    private static boolean CHECKED = false;

    /**
     * Checks if Essential mod is installed.
     */
    public static boolean isAvailable() {
        if (!CHECKED) {
            checkAvailability();
        }
        return MOD_AVAILABLE;
    }

    private static void checkAvailability() {
        CHECKED = true;

        if (PlatformHelper.isModLoaded("essential")) {
            MOD_AVAILABLE = true;
            return;
        }

        // Fallback class-based detection
        try {
            Class.forName("gg.essential.Essential");
            MOD_AVAILABLE = true;
        } catch (ClassNotFoundException e) {
            // Essential not detected
        }
    }

    /**
     * Finds the bottom-most widget in Essential's right-hand action rail.
     *
     * <p>Essential also owns controls below its title-screen player model. Selecting the globally
     * bottom-most widget anchors Quick Skin inside that model column, so only widgets on the right
     * half of the screen are eligible for the action-rail anchor.</p>
     *
     * @param screen The screen to scan
     * @return The bottom-most Essential widget, or null if none found
     */
    public static GuiEventListener findBottomEssentialWidget(Screen screen) {
        if (screen == null) {
            return null;
        }

        GuiEventListener bottomWidget = null;
        int maxBottom = -1;

        for (GuiEventListener listener : screen.children()) {
            String className = listener.getClass().getName();
            if (className.startsWith("gg.essential")) {
                if (listener instanceof net.minecraft.client.gui.components.AbstractWidget widget) {
                    if (widget.getX() < screen.width / 2) {
                        continue;
                    }
                    int bottom = widget.getY() + widget.getHeight();
                    if (bottom > maxBottom) {
                        maxBottom = bottom;
                        bottomWidget = listener;
                    }
                }
            }
        }

        return bottomWidget;
    }

    /**
     * Pre-registers the local player's saved skin/cape into PlayerAppearanceService
     * so that existing mixins can intercept Essential's player model lookups on the title screen.
     * Should be called after init, after world quit, and on screen init when Essential is present.
     */
    public static void registerMenuAppearance() {
        if (!isAvailable()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getUser() == null) return;

        // Don't attempt if TextureManager isn't ready yet (e.g. during early init)
        if (mc.getTextureManager() == null) {
            return;
        }

        UUID playerUuid = mc.getUser().getProfileId();
        if (playerUuid == null) return;

        ClientConfig config = ClientConfig.getInstance();
        LocalAssetManager assetManager = LocalAssetManager.getInstance();
        PlayerAppearanceService service = PlayerAppearanceService.getInstance();

        //? if >=1.21 {
        String skinId = null;
        String modelType = null;
        String capeId = null;

        // Prepare saved skin
        //?}
        if (!config.activeSkinHash.isEmpty()) {
            AssetMetadata metadata = assetManager.getMetadata(config.activeSkinHash);
            if (metadata != null) {
                //? if <1.21 {
                String skinId = "local_skin:" + config.activeSkinHash;
                String modelType = assetManager.getSkinModelPreference(config.activeSkinHash);
                service.applySkin(playerUuid, skinId, modelType);
                //?} else {
                skinId = "local_skin:" + config.activeSkinHash;
                modelType = assetManager.getSkinModelPreference(config.activeSkinHash);
                //?}
            }
        }

        // Prepare saved cape
        if (!config.activeCapeHash.isEmpty()) {
            //? if <1.21 {
            service.applyCape(playerUuid, config.activeCapeHash);
            //?} else {
            capeId = config.activeCapeHash;
        }

        // Apply both together using applyLook
        if (skinId != null || capeId != null) {
            service.applyLook(playerUuid, skinId, capeId, modelType);
            //?}
        }
    }
}
