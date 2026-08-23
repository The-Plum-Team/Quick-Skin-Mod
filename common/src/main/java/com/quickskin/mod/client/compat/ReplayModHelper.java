package com.quickskin.mod.client.compat;

import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.platform.PlatformHelper;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Optional compatibility bridge for ReplayMod playback.
 *
 * <p>During playback ReplayMod replaces the local player with its own camera entity, so the
 * recorded Quick Skin appearance belongs to a remote player that may not exist in the level yet
 * when the recorded payload is replayed. This helper observes the authoritative S2C application
 * path, remembers the recorded subject, and re-applies its look once the recorded player entity is
 * actually present.</p>
 *
 * <p>Every ReplayMod lookup is reflective and guarded: when ReplayMod is absent, or any optional
 * handle is missing, this class degrades to a no-op and the ordinary skin/cape path is untouched.
 * The watcher is pumped from the client thread by the callers below and is bounded by
 * {@link #MAX_WATCH_ATTEMPTS} attempts, so a replay that never spawns its subject cannot spin
 * forever.</p>
 */
@Environment(EnvType.CLIENT)
public final class ReplayModHelper {
    private static final Logger REPLAYLOG = LoggerFactory.getLogger("QuickSkin-ReplayMod");
    private static final String REPLAY_ENTRY_CLASS = "com.replaymod.replay.ReplayModReplay";
    private static final String CAMERA_ENTITY_CLASS = "com.replaymod.replay.camera.CameraEntity";
    /** Bounded client-tick budget for re-applying a recorded look, roughly one minute. */
    private static final int MAX_WATCH_ATTEMPTS = 20 * 60;

    private static final AtomicInteger INTERCEPTED = new AtomicInteger();
    private static final AtomicInteger REMAINING_ATTEMPTS = new AtomicInteger();
    private static final AtomicBoolean SKIN_APPLIED = new AtomicBoolean();
    private static final AtomicBoolean WATCHING = new AtomicBoolean();
    private static final AtomicBoolean PUMPING = new AtomicBoolean();

    private static volatile boolean modAvailable;
    private static volatile boolean availabilityChecked;
    private static volatile UUID targetPlayerId;

    private ReplayModHelper() {
    }

    /** Checks whether ReplayMod is loaded; the result is cached after the first successful probe. */
    public static boolean isAvailable() {
        if (!availabilityChecked) {
            availabilityChecked = true;
            if (PlatformHelper.isModLoaded("replaymod")) {
                modAvailable = true;
            } else {
                try {
                    Class.forName(REPLAY_ENTRY_CLASS);
                    modAvailable = true;
                } catch (ClassNotFoundException | LinkageError ignored) {
                    // ReplayMod not detected; every entry point below stays inert.
                }
            }
        }
        return modAvailable;
    }

    /**
     * Clears the evidence counters before a new playback session.
     *
     * <p>Called before ReplayMod is asked to start a replay so that intercepted-payload and
     * applied-look state can never be inherited from the recording session.</p>
     */
    public static void resetReplayEvidenceState() {
        INTERCEPTED.set(0);
        REMAINING_ATTEMPTS.set(0);
        SKIN_APPLIED.set(false);
        WATCHING.set(false);
        PUMPING.set(false);
        targetPlayerId = null;
    }

    /** Returns true while ReplayMod is playing a replay back through its camera entity. */
    public static boolean isInReplay() {
        if (!isAvailable()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return false;
        }
        if (isCameraEntity(minecraft.player)) {
            return true;
        }
        return activeReplayHandler() != null;
    }

    /**
     * Arms the bounded watcher that re-applies the recorded look to the replayed player.
     *
     * <p>The watcher owns no thread and no scheduler. It is pumped from the client thread by
     * {@link #noteNetworkAppearance(UUID)} and by the query methods below, so it can only ever run
     * where Minecraft state may be committed.</p>
     */
    public static void startReplayPlayerWatcher() {
        if (!isAvailable()) {
            return;
        }
        REMAINING_ATTEMPTS.set(MAX_WATCH_ATTEMPTS);
        WATCHING.set(true);
        pumpWatcher();
    }

    /**
     * Records that an authoritative Quick Skin appearance update was applied during playback.
     *
     * <p>This is the production hook for a recorded payload: ReplayMod feeds the recorded custom
     * payload back through the client packet listener, Quick Skin applies it, and this counter is
     * the evidence that the recorded look actually traversed the replay path instead of being
     * reconstructed locally.</p>
     */
    public static void noteNetworkAppearance(@Nullable UUID playerId) {
        if (playerId == null || !isInReplay()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.player != null
                && playerId.equals(minecraft.player.getUUID())) {
            // The camera entity is not a recorded subject.
            return;
        }
        targetPlayerId = playerId;
        if (INTERCEPTED.incrementAndGet() == 1) {
            REPLAYLOG.info("intercepted a recorded Quick Skin payload for replayed player {}",
                    playerId);
        }
        pumpWatcher();
    }

    /** Number of recorded Quick Skin payloads applied since the last evidence reset. */
    public static int getInterceptedPacketCount() {
        return INTERCEPTED.get();
    }

    /** True once the recorded look has been re-applied to a present replayed player entity. */
    public static boolean hasSkinBeenApplied() {
        pumpWatcher();
        return SKIN_APPLIED.get();
    }

    /**
     * Resolves the recorded player the replay is about, never ReplayMod's camera entity.
     *
     * <p>A subject that already carries a Quick Skin appearance wins, so a replay containing more
     * than one recorded player still selects the one the recorded payload described.</p>
     */
    @Nullable
    public static UUID getTargetPlayerUUID() {
        pumpWatcher();
        UUID cached = targetPlayerId;
        if (cached != null && resolvePlayer(cached) != null) {
            return cached;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            return cached;
        }
        UUID cameraId = minecraft.player == null ? null : minecraft.player.getUUID();
        UUID fallback = null;
        for (Player player : minecraft.level.players()) {
            if (player == null || isCameraEntity(player)) {
                continue;
            }
            UUID playerId = player.getUUID();
            if (playerId == null || playerId.equals(cameraId)) {
                continue;
            }
            if (hasRecordedSkin(playerId)) {
                targetPlayerId = playerId;
                return playerId;
            }
            if (fallback == null) {
                fallback = playerId;
            }
        }
        if (fallback != null) {
            targetPlayerId = fallback;
        }
        return targetPlayerId;
    }

    /** Resolves a replayed player entity by exact UUID, excluding ReplayMod's camera entity. */
    @Nullable
    public static AbstractClientPlayer getPlayerByUUID(@Nullable UUID playerId) {
        pumpWatcher();
        return resolvePlayer(playerId);
    }

    @Nullable
    private static AbstractClientPlayer resolvePlayer(@Nullable UUID playerId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (playerId == null || minecraft == null || minecraft.level == null) {
            return null;
        }
        Player player = minecraft.level.getPlayerByUUID(playerId);
        if (player instanceof AbstractClientPlayer clientPlayer && !isCameraEntity(clientPlayer)) {
            return clientPlayer;
        }
        return null;
    }

    private static boolean hasRecordedSkin(UUID playerId) {
        PlayerAppearance appearance = PlayerAppearanceService.getInstance().getAppearance(playerId);
        return appearance != null && appearance.getSkinId() != null
                && !appearance.getSkinId().isEmpty();
    }

    /**
     * Runs one bounded watcher step on the calling client thread.
     *
     * <p>Re-entrancy is refused because the step itself re-applies a look through the same
     * authoritative path that reports interceptions.</p>
     */
    private static void pumpWatcher() {
        if (!WATCHING.get() || SKIN_APPLIED.get()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || !minecraft.isSameThread()) {
            return;
        }
        if (!PUMPING.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!isInReplay()) {
                WATCHING.set(false);
                return;
            }
            if (REMAINING_ATTEMPTS.decrementAndGet() <= 0) {
                WATCHING.set(false);
                REPLAYLOG.warn("gave up re-applying the recorded Quick Skin look after {} attempts",
                        MAX_WATCH_ATTEMPTS);
                return;
            }
            UUID target = targetPlayerId;
            if (target == null || resolvePlayer(target) == null) {
                return;
            }
            PlayerAppearanceService appearances = PlayerAppearanceService.getInstance();
            PlayerAppearance appearance = appearances.getAppearance(target);
            if (appearance == null || appearance.getSkinId() == null
                    || appearance.getSkinId().isEmpty()) {
                return;
            }
            appearances.applyLookFromNetwork(target, appearance.getSkinId(),
                    appearance.getCapeId(), appearance.getModel());
            if (appearances.getSkinLocation(target) != null
                    && SKIN_APPLIED.compareAndSet(false, true)) {
                WATCHING.set(false);
                REPLAYLOG.info("re-applied the recorded Quick Skin look to replayed player {}",
                        target);
            }
        } catch (RuntimeException | LinkageError exception) {
            // A compatibility failure must degrade locally and never break playback.
            WATCHING.set(false);
            REPLAYLOG.warn("ReplayMod watcher stopped after a local failure", exception);
        } finally {
            PUMPING.set(false);
        }
    }

    @Nullable
    private static Object activeReplayHandler() {
        try {
            Class<?> replay = Class.forName(REPLAY_ENTRY_CLASS);
            Object instance = replay.getField("instance").get(null);
            return instance == null ? null : replay.getMethod("getReplayHandler").invoke(instance);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            return null;
        }
    }

    private static boolean isCameraEntity(@Nullable Object entity) {
        if (entity == null) {
            return false;
        }
        for (Class<?> type = entity.getClass(); type != null; type = type.getSuperclass()) {
            if (CAMERA_ENTITY_CLASS.equals(type.getName())) {
                return true;
            }
        }
        return false;
    }
}
