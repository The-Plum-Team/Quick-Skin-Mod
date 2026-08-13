package com.quickskin.mod.client.compat;

import com.quickskin.mod.QuickSkin;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Helper class for Replay Mod compatibility.
 * Detects when we're in a replay and provides utilities for finding the correct player.
 */
@Environment(EnvType.CLIENT)
public class ReplayModHelper {

    private static final String CAMERA_ENTITY_CLASS = "com.replaymod.replay.camera.CameraEntity";
    private static final AtomicBoolean skinAppliedInReplay = new AtomicBoolean(false);

    /** Returns whether the ReplayMod client API needed by this bridge is installed. */
    public static boolean isAvailable() {
        try {
            Class.forName(CAMERA_ENTITY_CLASS, false, ReplayModHelper.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    /**
     * Checks if the current player is a Replay Mod CameraEntity.
     * @return true if we're in a replay, false otherwise
     */
    public static boolean isInReplay() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return false;
        }
        return mc.player.getClass().getName().equals(CAMERA_ENTITY_CLASS);
    }

    /**
     * Gets the UUID of the player to apply skin changes to.
     * During replay, this finds the first real player entity (not the camera).
     * During normal gameplay, this returns mc.player.getUUID().
     *
     * @return The UUID to use for skin operations, or null if no suitable player found
     */
    @Nullable
    public static UUID getTargetPlayerUUID() {
        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null) {
            return null;
        }

        // If not in replay, use the normal player UUID
        if (!isInReplay()) {
            return mc.player.getUUID();
        }

        // In replay mode - find the first real player in the world
        if (mc.level != null) {
            for (Player player : mc.level.players()) {
                // Skip the camera entity
                if (!player.getClass().getName().equals(CAMERA_ENTITY_CLASS)) {
                    return player.getUUID();
                }
            }
        }

        // Fallback: Try to get from player info list
        if (mc.getConnection() != null) {
            for (PlayerInfo info : mc.getConnection().getListedOnlinePlayers()) {
                UUID uuid = info.getProfile().getId();
                // Skip if this looks like the camera entity UUID
                if (mc.player != null && !uuid.equals(mc.player.getUUID())) {
                    return uuid;
                }
            }
        }

        return null;
    }

    /**
     * Gets all recorded player UUIDs in the current replay.
     * Useful if you want to let the user choose which player to modify.
     *
     * @return List of player UUIDs (excluding the camera entity)
     */
    public static java.util.List<UUID> getAllRecordedPlayerUUIDs() {
        java.util.List<UUID> uuids = new java.util.ArrayList<>();
        Minecraft mc = Minecraft.getInstance();

        if (mc.level == null) {
            return uuids;
        }

        for (Player player : mc.level.players()) {
            if (!player.getClass().getName().equals(CAMERA_ENTITY_CLASS)) {
                uuids.add(player.getUUID());
            }
        }

        return uuids;
    }

    /**
     * Gets the AbstractClientPlayer for a given UUID in the current world.
     * Works in both replay and normal gameplay.
     *
     * @param uuid The player's UUID
     * @return The player entity, or null if not found
     */
    @Nullable
    public static AbstractClientPlayer getPlayerByUUID(UUID uuid) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || uuid == null) {
            return null;
        }

        Player player = mc.level.getPlayerByUUID(uuid);
        if (player instanceof AbstractClientPlayer) {
            return (AbstractClientPlayer) player;
        }
        return null;
    }

    /**
     * Resets the skin application state. Should be called when entering a new replay.
     */
    public static void resetSkinAppliedState() {
        skinAppliedInReplay.set(false);
    }

    /**
     * Checks if skins have been applied in the current replay.
     */
    public static boolean hasSkinBeenApplied() {
        return skinAppliedInReplay.get();
    }

    /**
     * Marks that skins have been applied in the current replay.
     */
    public static void markSkinApplied() {
        skinAppliedInReplay.set(true);
    }

    /**
     * Starts a background task that periodically checks for recorded players and applies skins.
     * This handles the case where player entities load after the initial join event.
     */
    public static void startReplayPlayerWatcher() {
        if (!isInReplay()) {
            return;
        }

        resetSkinAppliedState();

        // Start a background thread that checks for players every 200ms for up to 10 seconds
        Thread watcherThread = new Thread(() -> {
            int attempts = 0;
            int maxAttempts = 50; // 50 * 200ms = 10 seconds

            while (attempts < maxAttempts && !hasSkinBeenApplied() && isInReplay()) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    break;
                }

                attempts++;

                // Check for recorded players
                UUID targetUUID = getTargetPlayerUUID();
                if (targetUUID != null && !hasSkinBeenApplied()) {
                    // Found a player! Apply the saved skin on the main thread
                    Minecraft.getInstance().execute(() -> {
                        if (!hasSkinBeenApplied()) {
                            markSkinApplied();
                            applySavedSkinToPlayer(targetUUID);
                        }
                    });
                    break;
                }
            }

            
        }, "QuickSkin-ReplayPlayerWatcher");

        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    /**
     * Applies the saved skin configuration to the specified player UUID.
     */
    private static void applySavedSkinToPlayer(UUID targetPlayerId) {
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        com.quickskin.mod.client.services.LocalAssetManager assetManager =
                com.quickskin.mod.client.services.LocalAssetManager.getInstance();

        // Check if there's a saved skin
        if (!config.activeSkinHash.isEmpty()) {
            com.quickskin.mod.common.data.AssetMetadata metadata = assetManager.getMetadata(config.activeSkinHash);

            if (metadata != null) {
                String skinId = "local_skin:" + metadata.hash();
                String modelType = assetManager.getSkinModelPreference(config.activeSkinHash);

                com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                        .applySkin(targetPlayerId, skinId, modelType);
            }
        }

        // Check if there's a saved cape
        if (!config.activeCapeHash.isEmpty()) {
            String capeId = config.activeCapeHash;

            com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                    .applyCape(targetPlayerId, capeId);
        }
    }
}
