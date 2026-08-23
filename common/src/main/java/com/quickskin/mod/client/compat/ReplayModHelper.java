package com.quickskin.mod.client.compat;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.services.PlayerAppearanceService;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Compatibility integration for ReplayMod playback.
 *
 * <p>During playback the local player is ReplayMod's synthetic camera entity, so the recorded
 * player is an ordinary remote player that never re-negotiates with a server. Quick Skin therefore
 * has to bind the appearance carried by the <em>recorded</em> payload to the replay entity once
 * that entity exists, instead of waiting for a live session that will never happen.</p>
 *
 * <p>Every observation here is passive: the payload counter only records appearance updates that
 * the replay stream actually delivered, and the watcher never synthesizes an appearance from local
 * configuration. If ReplayMod is absent the helper stays inert and the normal skin/cape path is
 * untouched.</p>
 */
@Environment(EnvType.CLIENT)
public final class ReplayModHelper {
    private static final String CAMERA_ENTITY_CLASS = "com.replaymod.replay.camera.CameraEntity";

    private static final AtomicInteger INTERCEPTED_PAYLOADS = new AtomicInteger();
    /** Suppresses self-counting while the watcher rebinds an already intercepted appearance. */
    private static final AtomicBoolean REBINDING = new AtomicBoolean();

    private static volatile InternalEventBus.Subscription subscription;
    private static volatile UUID recordedPlayerId;
    private static volatile boolean skinApplied;
    private static volatile boolean watching;

    private ReplayModHelper() {
    }

    /**
     * Clears the observations of a previous playback and arms the payload listener again.
     *
     * <p>Callers reset immediately before starting a replay, while the client is disconnected, so
     * every counted update belongs to the replayed stream.</p>
     */
    public static synchronized void resetReplayEvidenceState() {
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
        INTERCEPTED_PAYLOADS.set(0);
        recordedPlayerId = null;
        skinApplied = false;
        watching = false;
        armListener();
    }

    /** Returns true once ReplayMod has taken over the client with its camera entity. */
    public static boolean isInReplay() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null && isCameraEntity(minecraft.player);
    }

    /** Starts binding replayed Quick Skin appearances to the recorded player. */
    public static synchronized void startReplayPlayerWatcher() {
        armListener();
        watching = true;
    }

    /**
     * Advances the watcher and returns the recorded player currently being tracked.
     *
     * @return the recorded player's UUID, or {@code null} while playback has not exposed one
     */
    @Nullable
    public static UUID getTargetPlayerUUID() {
        tick();
        return recordedPlayerId;
    }

    /** Resolves a client player entity, tolerating a target that is not loaded yet. */
    @Nullable
    public static AbstractClientPlayer getPlayerByUUID(@Nullable UUID playerId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (playerId == null || minecraft == null || minecraft.level == null) {
            return null;
        }
        Player player = minecraft.level.getPlayerByUUID(playerId);
        return player instanceof AbstractClientPlayer clientPlayer ? clientPlayer : null;
    }

    /** Number of Quick Skin appearance payloads the replay stream delivered since the last reset. */
    public static int getInterceptedPacketCount() {
        return INTERCEPTED_PAYLOADS.get();
    }

    /** True once an intercepted appearance is bound to a resolved skin texture for the target. */
    public static boolean hasSkinBeenApplied() {
        return skinApplied;
    }

    private static synchronized void armListener() {
        if (subscription == null) {
            subscription = InternalEventBus.getInstance().register(
                    PlayerAppearanceUpdateEvent.class, ReplayModHelper::onAppearanceUpdate);
        }
    }

    private static void onAppearanceUpdate(PlayerAppearanceUpdateEvent event) {
        if (REBINDING.get()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        UUID cameraId = minecraft == null || minecraft.player == null
                ? null : minecraft.player.getUUID();
        if (event.playerId().equals(cameraId)) {
            return;
        }
        INTERCEPTED_PAYLOADS.incrementAndGet();
    }

    private static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!watching || minecraft == null || minecraft.level == null || !isInReplay()) {
            return;
        }
        UUID target = recordedPlayerId;
        if (target == null) {
            target = findRecordedPlayer(minecraft);
            if (target == null) {
                return;
            }
            recordedPlayerId = target;
            QuickSkin.LOGGER.info("QuickSkin is tracking recorded ReplayMod player {}", target);
        }
        if (INTERCEPTED_PAYLOADS.get() <= 0 || getPlayerByUUID(target) == null) {
            return;
        }
        PlayerAppearanceService appearances = PlayerAppearanceService.getInstance();
        PlayerAppearance appearance = appearances.getAppearance(target);
        if (appearance == null || isBlank(appearance.getSkinId())) {
            return;
        }
        if (!skinApplied || appearances.getSkinLocation(target) == null) {
            rebind(appearances, target, appearance);
            skinApplied = appearances.getSkinLocation(target) != null;
        }
    }

    /**
     * Re-applies the appearance the replay already delivered so the recorded entity resolves its
     * texture and renderer state. This never invents an appearance the stream did not carry.
     */
    private static void rebind(
            PlayerAppearanceService appearances, UUID target, PlayerAppearance appearance) {
        if (!REBINDING.compareAndSet(false, true)) {
            return;
        }
        try {
            appearances.applyLook(
                    target,
                    emptyToNull(appearance.getSkinId()),
                    emptyToNull(appearance.getCapeId()),
                    appearance.getModel());
            appearances.refreshPlayerRenderer(target);
        } catch (RuntimeException | LinkageError error) {
            QuickSkin.LOGGER.warn(
                    "QuickSkin could not bind the replayed appearance for {}", target, error);
        } finally {
            REBINDING.set(false);
        }
    }

    @Nullable
    private static UUID findRecordedPlayer(Minecraft minecraft) {
        PlayerAppearanceService appearances = PlayerAppearanceService.getInstance();
        UUID fallback = null;
        for (Player player : minecraft.level.players()) {
            if (player == minecraft.player || isCameraEntity(player)) {
                continue;
            }
            UUID playerId = player.getUUID();
            if (appearances.hasActiveSkin(playerId)) {
                return playerId;
            }
            if (fallback == null) {
                fallback = playerId;
            }
        }
        return fallback;
    }

    /**
     * Detects ReplayMod's camera by walking the class hierarchy by name, so the helper links
     * cleanly when ReplayMod is not installed.
     */
    private static boolean isCameraEntity(@Nullable Player player) {
        if (player == null) {
            return false;
        }
        for (Class<?> type = player.getClass(); type != null; type = type.getSuperclass()) {
            if (CAMERA_ENTITY_CLASS.equals(type.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isEmpty();
    }

    @Nullable
    private static String emptyToNull(@Nullable String value) {
        return isBlank(value) ? null : value;
    }
}
