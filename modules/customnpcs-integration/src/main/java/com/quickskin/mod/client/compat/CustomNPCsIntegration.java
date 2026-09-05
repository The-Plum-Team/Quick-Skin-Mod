package com.quickskin.mod.client.compat;

import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.platform.PlatformHelper;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
//? if <1.21 {
import net.minecraft.resources.ResourceLocation;
//?} else if <1.21.9 {
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
//?} else if <1.21.11 {
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
//?}

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

/**
 * Compatibility integration for CustomNPCs-Unofficial mod.
 *
 * CustomNPCs-Unofficial has its own player skin handling that can conflict with Quick-Skin-Mod.
 * This integration:
 * 1. Detects when CustomNPCs is installed
 * 2. Clears any skin caches that CustomNPCs might maintain
 * 3. Provides hooks to refresh player appearance when needed
 */
@Environment(EnvType.CLIENT)
public class CustomNPCsIntegration {
    private static boolean MOD_AVAILABLE = false;
    private static boolean CHECKED = false;

    // Reflected references (lazily initialized)
    private static Class<?> npcApiClass;
    private static Class<?> playerDataClass;

    // Cache for PlayerSkin textures that we've overridden
    // This helps detect when CustomNPCs might be reverting our changes
//? if <1.21.11 {
    private static final Map<UUID, ResourceLocation> lastAppliedSkins = new java.util.concurrent.ConcurrentHashMap<>();
//?} else {
    private static final Map<UUID, Identifier> lastAppliedSkins = new java.util.concurrent.ConcurrentHashMap<>();
//?}

    /**
     * Checks if CustomNPCs-Unofficial is installed.
     */
    public static boolean isAvailable() {
        if (!CHECKED) {
            checkAvailability();
        }
        return MOD_AVAILABLE;
    }

    private static void checkAvailability() {
        CHECKED = true;

        // Check multiple possible mod IDs (CustomNPCs has had different IDs over versions)
        String[] possibleModIds = {
            "customnpcs",
            "customnpcs-unofficial",
            "customnpcsunofficial",
            "cnpcs"
        };

        for (String modId : possibleModIds) {
            if (PlatformHelper.isModLoaded(modId)) {
                MOD_AVAILABLE = true;
                initializeReflection();
                return;
            }
        }

        // Also try class-based detection as fallback
        try {
            Class.forName("noppes.npcs.api.NpcAPI");
            MOD_AVAILABLE = true;
            initializeReflection();
        } catch (ClassNotFoundException e) {
        }
    }

    private static void initializeReflection() {
        try {
            // Try to get NpcAPI class
            npcApiClass = Class.forName("noppes.npcs.api.NpcAPI");

            // Try to find any player data or skin cache classes
            // These class names may vary between versions
            String[] possiblePlayerDataClasses = {
                "noppes.npcs.controllers.PlayerDataController",
                "noppes.npcs.controllers.data.PlayerDataController",
                "noppes.npcs.client.ClientCacheHandler",
                "noppes.npcs.client.ClientProxy"
            };

            for (String className : possiblePlayerDataClasses) {
                try {
                    playerDataClass = Class.forName(className);
                    break;
                } catch (ClassNotFoundException ignored) {
                }
            }

        } catch (Exception e) {
        }
    }

    /**
     * Called when Quick-Skin-Mod applies a new skin to a player.
     * This notifies the CustomNPCs integration to clear any cached skin data.
     *
     * @param playerId The UUID of the player whose skin was changed
     * @param skinLocation The new skin texture location
     */
//? if <1.21.11 {
    public static void onSkinApplied(UUID playerId, ResourceLocation skinLocation) {
//?} else {
    public static void onSkinApplied(UUID playerId, Identifier skinLocation) {
//?}
        if (!isAvailable()) {
            return;
        }

        lastAppliedSkins.put(playerId, skinLocation);

        // Try to invalidate any CustomNPCs skin cache
        invalidateCustomNPCsSkinCache(playerId);
    }

    /**
     * Attempts to invalidate any skin cache that CustomNPCs might maintain.
     * This uses reflection to avoid hard dependencies.
     */
    private static void invalidateCustomNPCsSkinCache(UUID playerId) {
        if (playerDataClass == null) {
            return;
        }

        try {
            // Try to find and invoke cache clearing methods
            // The exact method name depends on CustomNPCs version
            String[] possibleMethods = {
                "clearSkinCache",
                "invalidateCache",
                "refreshPlayerData",
                "clearCache"
            };

            for (String methodName : possibleMethods) {
                try {
                    Method method = playerDataClass.getDeclaredMethod(methodName, UUID.class);
                    method.setAccessible(true);

                    // Get instance if needed
                    Object instance = null;
                    try {
                        Method getInstance = playerDataClass.getDeclaredMethod("getInstance");
                        getInstance.setAccessible(true);
                        instance = getInstance.invoke(null);
                    } catch (Exception ignored) {
                        // Might be static method
                    }

                    method.invoke(instance, playerId);
                    return;
                } catch (NoSuchMethodException ignored) {
                }
            }
        } catch (Exception e) {
        }
    }

    /**
     * Gets the Quick-Skin-Mod skin for a player, ensuring CustomNPCs doesn't override it.
     * This is called from mixins to provide a consistent skin regardless of CustomNPCs state.
     *
     * @param player The player to get skin for
     * @param defaultSkin The default skin (from vanilla or CustomNPCs)
     * @return The Quick-Skin-Mod skin if available, otherwise the default
     */
//? if <1.21.11 {
    public static ResourceLocation getOverrideSkin(AbstractClientPlayer player, ResourceLocation defaultSkin) {
//?} else {
    public static Identifier getOverrideSkin(AbstractClientPlayer player, Identifier defaultSkin) {
//?}
        if (player == null) {
            return defaultSkin;
        }

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();
        UUID playerId = player.getUUID();

        // Check if Quick-Skin-Mod has an active skin for this player
        if (service.hasActiveSkin(playerId)) {
//? if <1.21.11 {
            ResourceLocation quickSkin = service.getSkinLocation(playerId);
//?} else {
            Identifier quickSkin = service.getSkinLocation(playerId);
//?}
            if (quickSkin != null) {
                return quickSkin;
            }
        }

        return defaultSkin;
    }

    /**
     * Gets the Quick-Skin-Mod skin by player ID, for use with PlayerInfo.
     *
     * @param playerId The player UUID
     * @param defaultSkin The default skin
     * @return The Quick-Skin-Mod skin if available, otherwise the default
     */
//? if <1.21.11 {
    public static ResourceLocation getOverrideSkinByUUID(UUID playerId, ResourceLocation defaultSkin) {
//?} else {
    public static Identifier getOverrideSkinByUUID(UUID playerId, Identifier defaultSkin) {
//?}
        if (playerId == null) {
            return defaultSkin;
        }

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();

        if (service.hasActiveSkin(playerId)) {
//? if <1.21.11 {
            ResourceLocation quickSkin = service.getSkinLocation(playerId);
//?} else {
            Identifier quickSkin = service.getSkinLocation(playerId);
//?}
            if (quickSkin != null) {
                return quickSkin;
            }
        }

        return defaultSkin;
    }

//? if <1.21 {
//?} else if <1.21.9 {
    /**
     * Gets the Quick-Skin-Mod PlayerSkin, ensuring CustomNPCs doesn't override it.
     * This is the 1.21.1 version that works with the PlayerSkin record.
     *
     * @param playerId The player UUID
     * @param originalSkin The original PlayerSkin (from vanilla or CustomNPCs)
     * @return The modified PlayerSkin if Quick-Skin-Mod is active, otherwise the original
     */
    public static PlayerSkin getOverridePlayerSkin(UUID playerId, PlayerSkin originalSkin) {
        if (playerId == null || originalSkin == null) {
            return originalSkin;
        }

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();

        boolean hasCustomSkin = service.hasActiveSkin(playerId);
        boolean hasCustomCape = service.hasActiveCape(playerId);
        boolean hasModelOverride = service.hasModelOverride(playerId);

        if (!hasCustomSkin && !hasCustomCape && !hasModelOverride) {
            return originalSkin;
        }

        ResourceLocation skinTexture = originalSkin.texture();
        PlayerSkin.Model skinModel = originalSkin.model();
        ResourceLocation capeTexture = originalSkin.capeTexture();

        // Override skin texture
        if (hasCustomSkin) {
            ResourceLocation customSkin = service.getSkinLocation(playerId);
            if (customSkin != null) {
                skinTexture = customSkin;
            }
        }

        // Override model
        if (hasCustomSkin || hasModelOverride) {
            String customModel = service.getModelName(playerId);
            if (customModel != null) {
                skinModel = "slim".equals(customModel) ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE;
            }
        }

        // Override cape
        if (hasCustomCape) {
            ResourceLocation customCape = service.getCapeLocation(playerId);
            if (customCape != null) {
                capeTexture = customCape;
            } else {
                // Check if we're explicitly hiding the cape
                com.quickskin.mod.common.data.PlayerAppearance appearance = service.getAppearance(playerId);
                if (appearance != null && ("__NONE__".equals(appearance.getCapeId()) || appearance.getCapeId().isEmpty())) {
                    capeTexture = null;
                }
            }
        }

        return new PlayerSkin(
            skinTexture,
            originalSkin.textureUrl(),
            capeTexture,
            originalSkin.elytraTexture(),
            skinModel,
            originalSkin.secure()
        );
    }
//?} else {
    /**
     * Gets the Quick-Skin-Mod PlayerSkin, ensuring CustomNPCs doesn't override it.
     * This is the 1.21.1 version that works with the PlayerSkin record.
     *
     * @param playerId The player UUID
     * @param originalSkin The original PlayerSkin (from vanilla or CustomNPCs)
     * @return The modified PlayerSkin if Quick-Skin-Mod is active, otherwise the original
     */
    public static PlayerSkin getOverridePlayerSkin(UUID playerId, PlayerSkin originalSkin) {
        if (playerId == null || originalSkin == null) {
            return originalSkin;
        }

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();

        boolean hasCustomSkin = service.hasActiveSkin(playerId);
        boolean hasCustomCape = service.hasActiveCape(playerId);
        boolean hasModelOverride = service.hasModelOverride(playerId);

        if (!hasCustomSkin && !hasCustomCape && !hasModelOverride) {
            return originalSkin;
        }

        //? if <1.21.11 {
        ResourceLocation skinTexture = originalSkin.body().texturePath();
        //?} else {
        Identifier skinTexture = originalSkin.body().texturePath();
        //?}
        PlayerModelType skinModel = originalSkin.model();
        //? if <1.21.11 {
        ResourceLocation capeTexture = originalSkin.cape() != null ? originalSkin.cape().texturePath() : null;
        //?} else {
        Identifier capeTexture = originalSkin.cape() != null ? originalSkin.cape().texturePath() : null;
        //?}

        // Override skin texture
        if (hasCustomSkin) {
            //? if <1.21.11 {
            ResourceLocation customSkin = service.getSkinLocation(playerId);
            //?} else {
            Identifier customSkin = service.getSkinLocation(playerId);
            //?}
            if (customSkin != null) {
                skinTexture = customSkin;
            }
        }

        // Override model
        if (hasCustomSkin || hasModelOverride) {
            String customModel = service.getModelName(playerId);
            if (customModel != null) {
                skinModel = "slim".equals(customModel) ? PlayerModelType.SLIM : PlayerModelType.WIDE;
            }
        }

        // Override cape
        if (hasCustomCape) {
            //? if <1.21.11 {
            ResourceLocation customCape = service.getCapeLocation(playerId);
            //?} else {
            Identifier customCape = service.getCapeLocation(playerId);
            //?}
            if (customCape != null) {
                capeTexture = customCape;
            } else {
                // Check if we're explicitly hiding the cape
                com.quickskin.mod.common.data.PlayerAppearance appearance = service.getAppearance(playerId);
                if (appearance != null && ("__NONE__".equals(appearance.getCapeId()) || appearance.getCapeId().isEmpty())) {
                    capeTexture = null;
                }
            }
        }

        return new PlayerSkin(
            new ClientAsset.ResourceTexture(skinTexture, skinTexture),
            capeTexture != null ? new ClientAsset.ResourceTexture(capeTexture, capeTexture) : null,
            originalSkin.elytra(),
            skinModel,
            originalSkin.secure()
        );
    }
//?}

    /**
     * Called to check if a skin has been unexpectedly changed (possibly by CustomNPCs).
     * This can be used to detect and correct skin conflicts.
     *
     * @param playerId The player UUID
     * @param currentSkin The current skin being rendered
     * @return true if the skin appears to have been changed by another mod
     */
//? if <1.21.11 {
    public static boolean detectSkinConflict(UUID playerId, ResourceLocation currentSkin) {
//?} else {
    public static boolean detectSkinConflict(UUID playerId, Identifier currentSkin) {
//?}
        if (!isAvailable()) {
            return false;
        }

//? if <1.21.11 {
        ResourceLocation expectedSkin = lastAppliedSkins.get(playerId);
//?} else {
        Identifier expectedSkin = lastAppliedSkins.get(playerId);
//?}
        if (expectedSkin != null && !expectedSkin.equals(currentSkin)) {
            return true;
        }

        return false;
    }

    /**
     * Clears the tracked skin for a player (e.g., when they reset to default).
     */
    public static void clearTrackedSkin(UUID playerId) {
        lastAppliedSkins.remove(playerId);
    }

    /** Drops player identities retained by the connection that just ended. */
    public static void clearTrackedSkins() {
        lastAppliedSkins.clear();
    }

    /**
     * Forces a refresh of the player's appearance, which can help resolve conflicts.
     * This is called after CustomNPCs events that might modify player data.
     */
    public static void forceRefreshPlayer(UUID playerId) {
        if (!isAvailable()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();
        if (service.hasActiveSkin(playerId)) {
            service.refreshPlayerRenderer(playerId);
        }
    }
}
