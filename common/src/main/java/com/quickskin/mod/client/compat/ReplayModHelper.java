package com.quickskin.mod.client.compat;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.common.event.InternalEventBus;
import com.quickskin.mod.common.event.PlayerAppearanceUpdateEvent;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Compatibility integration for ReplayMod playback.
 *
 * <p>ReplayMod replaces the local player with its own camera entity while a recording plays back,
 * so the recorded Quick Skin appearance belongs to a remote player that is not
 * {@code Minecraft.player}. This helper resolves that recorded player and records bounded evidence
 * that a recorded Quick Skin payload actually traversed the client appearance path during
 * playback.</p>
 *
 * <p>ReplayMod is optional. Every entry point is reflection-guarded and degrades to "not in a
 * replay" when the mod, its playback API, or the client level is absent, so the normal skin/cape
 * path is never affected.</p>
 */
@Environment(EnvType.CLIENT)
public final class ReplayModHelper {
    private static final String REPLAY_MOD_CLASS = "com.replaymod.replay.ReplayModReplay";
    private static final String CAMERA_ENTITY_CLASS = "com.replaymod.replay.camera.CameraEntity";

    /** Evidence counters stay bounded; playback can replay an unbounded number of payloads. */
    private static final int MAX_TRACKED_PAYLOADS = 1_000_000;

    private static final AtomicInteger INTERCEPTED_PAYLOADS = new AtomicInteger();

    private static volatile InternalEventBus.Subscription subscription;
    private static volatile UUID recordedPlayer;
    private static volatile boolean skinApplied;
    private static volatile Class<?> replayModClass;
    private static volatile boolean replayApiAbsent;

    private ReplayModHelper() {
    }

    /** Clears the recorded-playback evidence before a new replay is started. */
    public static void resetReplayEvidenceState() {
        INTERCEPTED_PAYLOADS.set(0);
        skinApplied = false;
        recordedPlayer = null;
        ensureWatching();
    }

    /** Returns true while ReplayMod is playing a recording back on this client. */
    public static boolean isInReplay() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            return false;
        }
        if (isCameraEntity(minecraft.player)) {
            return true;
        }
        return activeReplayHandler() != null;
    }

    /**
     * Starts observing recorded appearance updates and resolves the recorded player.
     *
     * <p>Idempotent: the single bus subscription is installed at most once.</p>
     */
    public static void startReplayPlayerWatcher() {
        ensureWatching();
        if (recordedPlayer == null) {
            recordedPlayer = findRecordedPlayer();
        }
    }

    /** The recorded player being played back, never ReplayMod's camera entity. */
    @Nullable
    public static UUID getTargetPlayerUUID() {
        UUID recorded = recordedPlayer;
        if (recorded != null && resolvePlayer(recorded) != null) {
            return recorded;
        }
        UUID discovered = findRecordedPlayer();
        if (discovered != null) {
            recordedPlayer = discovered;
            return discovered;
        }
        return recorded;
    }

    /** Resolves a client player by exact UUID, or null when it is not in the replayed level. */
    @Nullable
    public static AbstractClientPlayer getPlayerByUUID(@Nullable UUID playerId) {
        return resolvePlayer(playerId) instanceof AbstractClientPlayer clientPlayer
                ? clientPlayer
                : null;
    }

    /** How many recorded Quick Skin appearance payloads were observed during this playback. */
    public static int getInterceptedPacketCount() {
        return INTERCEPTED_PAYLOADS.get();
    }

    /** True once a recorded payload resolved to a real skin texture for the recorded player. */
    public static boolean hasSkinBeenApplied() {
        return skinApplied;
    }

    private static synchronized void ensureWatching() {
        if (subscription != null) {
            return;
        }
        subscription = InternalEventBus.getInstance().register(
                PlayerAppearanceUpdateEvent.class, ReplayModHelper::onAppearanceUpdate);
    }

    private static void onAppearanceUpdate(PlayerAppearanceUpdateEvent event) {
        if (!isInReplay()) {
            return;
        }
        UUID playerId = event.playerId();
        if (isCameraEntity(resolvePlayer(playerId))) {
            return;
        }
        INTERCEPTED_PAYLOADS.accumulateAndGet(1, (current, increment) ->
                current >= MAX_TRACKED_PAYLOADS ? current : current + increment);
        recordedPlayer = playerId;

        PlayerAppearance appearance = event.appearance();
        String skinId = appearance.getSkinId();
        if (skinId != null && !skinId.isEmpty() && appearance.getSkinLocation() != null) {
            skinApplied = true;
        }
    }

    @Nullable
    private static UUID findRecordedPlayer() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            return null;
        }
        for (Player player : minecraft.level.players()) {
            if (player == minecraft.player || isCameraEntity(player)) {
                continue;
            }
            if (player instanceof AbstractClientPlayer) {
                return player.getUUID();
            }
        }
        return null;
    }

    @Nullable
    private static Player resolvePlayer(@Nullable UUID playerId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (playerId == null || minecraft == null || minecraft.level == null) {
            return null;
        }
        return minecraft.level.getPlayerByUUID(playerId);
    }

    /**
     * Class-name comparison rather than {@code instanceof}: ReplayMod's camera entity must never
     * become a compile-time dependency of the base mod.
     */
    private static boolean isCameraEntity(@Nullable Player player) {
        for (Class<?> type = player == null ? null : player.getClass();
                type != null; type = type.getSuperclass()) {
            if (CAMERA_ENTITY_CLASS.equals(type.getName())) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static Object activeReplayHandler() {
        if (replayApiAbsent) {
            return null;
        }
        try {
            Class<?> replayMod = replayModClass;
            if (replayMod == null) {
                replayMod = Class.forName(
                        REPLAY_MOD_CLASS, false, ReplayModHelper.class.getClassLoader());
                replayModClass = replayMod;
            }
            Object instance = replayMod.getField("instance").get(null);
            if (instance == null) {
                return null;
            }
            return replayMod.getMethod("getReplayHandler").invoke(instance);
        } catch (ClassNotFoundException | NoSuchFieldException | NoSuchMethodException absent) {
            replayApiAbsent = true;
            QuickSkin.LOGGER.debug(
                    "ReplayMod playback API is unavailable; Quick Skin keeps its normal path");
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            QuickSkin.LOGGER.debug("ReplayMod playback state could not be read", failure);
            return null;
        }
    }
}
