package com.quickskin.mod.client.compat;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Helper class for Replay Mod compatibility.
 * Detects when we're in a replay and provides utilities for finding the correct player.
 */
@Environment(EnvType.CLIENT)
public class ReplayModHelper {

    private static final String CAMERA_ENTITY_CLASS = "com.replaymod.replay.camera.CameraEntity";
    private static final AtomicBoolean skinAppliedInReplay = new AtomicBoolean(false);
    private static final AtomicInteger interceptedPacketCount = new AtomicInteger();
    private static final int MAX_WATCHER_TICKS = 20 * 60;
    private static final Object WATCHER_LOCK = new Object();
    private static boolean watcherActive;
    private static int watcherTicks;

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
     * During replay, this finds the recorded player entity (not the camera). Call it on the
     * Minecraft thread.
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

        // In replay mode - the recorded player whose recorded appearance carries a skin, else
        // the first real player in the world
        UUID recorded = recordedPlayerWithSkin();
        if (recorded != null) {
            return recorded;
        }
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

    /** Resets the observable state before opening a new replay file. */
    public static void resetReplayEvidenceState() {
        synchronized (WATCHER_LOCK) {
            watcherActive = false;
        }
        skinAppliedInReplay.set(false);
        interceptedPacketCount.set(0);
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

    /** Records that a Quick Skin payload was handled through ReplayMod's fake connection. */
    public static void markQuickSkinPacketIntercepted() {
        interceptedPacketCount.incrementAndGet();
    }

    /** Number of Quick Skin payloads observed during the current replay playback. */
    public static int getInterceptedPacketCount() {
        return interceptedPacketCount.get();
    }

    /**
     * Starts watching the replay for the recorded player. The watch runs from the client tick
     * ({@link #tick()}), on the Minecraft thread, because the level's players may only be read
     * there; a second call while the watch runs is a no-op.
     */
    public static void startReplayPlayerWatcher() {
        if (!isInReplay()) {
            return;
        }
        synchronized (WATCHER_LOCK) {
            if (watcherActive) {
                return;
            }
            resetSkinAppliedState();
            watcherTicks = 0;
            watcherActive = true;
        }
    }

    /** One bounded pass, called on the Minecraft thread by the client composition root. */
    public static void tick() {
        synchronized (WATCHER_LOCK) {
            if (!watcherActive) {
                return;
            }
            if (hasSkinBeenApplied() || !isInReplay() || ++watcherTicks > MAX_WATCHER_TICKS) {
                watcherActive = false;
                return;
            }
        }
        try {
            refreshRecordedAppearance();
        } catch (RuntimeException | LinkageError error) {
            synchronized (WATCHER_LOCK) {
                watcherActive = false;
            }
            com.quickskin.mod.platform.QuickSkinInfo.LOGGER.debug(
                    "QuickSkin ReplayMod watcher stopped after a failure", error);
        }
    }

    /**
     * Pushes the appearance the recording carried for its player to the renderer once its skin has
     * loaded. The recorded payloads decide that appearance; the viewer's own saved skin and cape
     * are never applied to a recorded participant.
     */
    private static void refreshRecordedAppearance() {
        UUID target = recordedPlayerWithSkin();
        if (target == null || getPlayerByUUID(target) == null) {
            return;
        }
        com.quickskin.mod.client.services.PlayerAppearanceService service =
                com.quickskin.mod.client.services.PlayerAppearanceService.getInstance();
        if (service.getSkinLocation(target) != null) {
            service.refreshPlayerRenderer(target);
            markSkinApplied();
        }
    }

    /** The first recorded player, never the camera, whose recorded appearance carries a skin. */
    @Nullable
    private static UUID recordedPlayerWithSkin() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        com.quickskin.mod.client.services.PlayerAppearanceService service =
                com.quickskin.mod.client.services.PlayerAppearanceService.getInstance();
        for (Player player : mc.level.players()) {
            if (player == mc.player || player.getClass().getName().equals(CAMERA_ENTITY_CLASS)) {
                continue;
            }
            com.quickskin.mod.common.data.PlayerAppearance appearance =
                    service.getAppearance(player.getUUID());
            String skinId = appearance == null ? null : appearance.getSkinId();
            if (skinId != null && !skinId.isEmpty()) {
                return player.getUUID();
            }
        }
        return null;
    }
}
