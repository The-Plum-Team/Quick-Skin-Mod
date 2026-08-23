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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Integration with the ReplayMod mod using reflection.
 *
 * <p>ReplayMod is optional. Every entry point degrades to "no replay is running" when the mod, its
 * playback entry point, or its camera entity is absent, so the ordinary skin/cape path is never
 * affected by this class.</p>
 *
 * <p>During playback ReplayMod replaces the local player with its own camera entity and feeds the
 * recorded packet stream through the normal client packet listener. Quick Skin's recorded
 * appearance payloads therefore reach {@code PlayerAppearanceService} exactly as they did while
 * recording, and the renderer mixins read that live service state every frame. This helper does not
 * re-apply anything on top of that; it observes the replayed stream so that playback can be
 * attributed to the recorded player instead of the camera entity.</p>
 */
@Environment(EnvType.CLIENT)
public final class ReplayModHelper {

    /** ReplayMod's playback entry point; absent whenever the optional mod is not installed. */
    private static final String REPLAY_MOD_CLASS = "com.replaymod.replay.ReplayModReplay";

    /** The stand-in local player ReplayMod installs for playback; never a recorded player. */
    private static final String CAMERA_ENTITY_CLASS = "com.replaymod.replay.camera.CameraEntity";

    /**
     * Hard cap for the observed-update counter. A replay is untrusted input, so the evidence
     * counter saturates instead of growing with the recording length.
     */
    private static final int MAX_OBSERVED_UPDATES = 4096;

    private static volatile boolean reflectionResolved;
    private static volatile boolean replayModPresent;
    private static volatile Field replayModInstanceField;
    private static volatile Method replayHandlerMethod;
    private static volatile Class<?> cameraEntityClass;

    private static volatile InternalEventBus.Subscription subscription;
    private static volatile int observedUpdates;
    private static volatile UUID targetPlayerId;
    private static volatile boolean skinApplied;

    private ReplayModHelper() {
    }

    /**
     * Reports whether ReplayMod playback is active and has already swapped the local player for its
     * camera entity. Recording, the main menu, and ordinary multiplayer all report {@code false}.
     */
    public static boolean isInReplay() {
        if (!isPlaybackActive()) {
            return false;
        }
        Class<?> camera = cameraEntityClass;
        if (camera == null) {
            // ReplayMod is running but its camera class was not resolvable; the active replay
            // handler is the only evidence available on this loader.
            return true;
        }
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null && camera.isInstance(minecraft.player);
    }

    /**
     * Starts observing the replayed appearance stream. The watcher is idempotent, holds exactly one
     * bounded subscription, and releases it as soon as playback stops.
     */
    public static synchronized void startReplayPlayerWatcher() {
        if (subscription != null) {
            return;
        }
        try {
            subscription = InternalEventBus.getInstance().register(
                    PlayerAppearanceUpdateEvent.class, ReplayModHelper::onAppearanceUpdate);
        } catch (RuntimeException | LinkageError failure) {
            QuickSkin.LOGGER.warn("Quick Skin could not observe ReplayMod playback", failure);
        }
    }

    /** Releases the playback subscription. Safe to call when no watcher is running. */
    public static synchronized void stopReplayPlayerWatcher() {
        InternalEventBus.Subscription active = subscription;
        subscription = null;
        if (active == null) {
            return;
        }
        try {
            active.close();
        } catch (RuntimeException | LinkageError failure) {
            QuickSkin.LOGGER.warn("Quick Skin could not release its ReplayMod watcher", failure);
        }
    }

    /**
     * Stops the watcher and clears everything observed for the previous replay, so a new playback
     * can never inherit the previous recording's target or counters.
     */
    public static void resetReplayEvidenceState() {
        stopReplayPlayerWatcher();
        observedUpdates = 0;
        targetPlayerId = null;
        skinApplied = false;
    }

    /**
     * The recorded player whose Quick Skin appearance was replayed, or {@code null} before any
     * recorded appearance has arrived. This is never ReplayMod's camera entity.
     */
    @Nullable
    public static UUID getTargetPlayerUUID() {
        return targetPlayerId;
    }

    /**
     * Resolves a recorded player entity by UUID. ReplayMod's camera entity is deliberately excluded
     * so callers cannot mistake the playback stand-in for a recorded player.
     */
    @Nullable
    public static AbstractClientPlayer getPlayerByUUID(@Nullable UUID playerId) {
        if (playerId == null) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            return null;
        }
        Player player = minecraft.level.getPlayerByUUID(playerId);
        if (!(player instanceof AbstractClientPlayer clientPlayer)) {
            return null;
        }
        resolveReflection();
        Class<?> camera = cameraEntityClass;
        return camera != null && camera.isInstance(clientPlayer) ? null : clientPlayer;
    }

    /**
     * Number of recorded Quick Skin appearance updates that traversed the client pipeline during
     * the current playback. Saturates at {@link #MAX_OBSERVED_UPDATES}.
     */
    public static int getInterceptedPacketCount() {
        return observedUpdates;
    }

    /**
     * Reports whether the recorded player currently resolves to a Quick Skin skin. Both the stored
     * appearance and the resolved texture are required, so a replayed identifier without its
     * texture bytes never counts as applied.
     */
    public static boolean hasSkinBeenApplied() {
        UUID target = targetPlayerId;
        if (target == null) {
            return false;
        }
        if (skinApplied) {
            return true;
        }
        PlayerAppearanceService service = PlayerAppearanceService.getInstance();
        if (service == null) {
            return false;
        }
        PlayerAppearance appearance = service.getAppearance(target);
        String skinId = appearance == null ? null : appearance.getSkinId();
        if (skinId == null || skinId.isEmpty() || service.getSkinLocation(target) == null) {
            return false;
        }
        skinApplied = true;
        return true;
    }

    /**
     * Records one replayed appearance update. Updates for ReplayMod's camera entity are ignored:
     * the camera carries a synthetic identity that never belongs to the recording.
     */
    private static void onAppearanceUpdate(PlayerAppearanceUpdateEvent event) {
        if (!isPlaybackActive()) {
            stopReplayPlayerWatcher();
            return;
        }
        UUID playerId = event.playerId();
        if (playerId == null || playerId.equals(localPlayerId())) {
            return;
        }
        int seen = observedUpdates;
        if (seen < MAX_OBSERVED_UPDATES) {
            observedUpdates = seen + 1;
        }
        if (targetPlayerId != null) {
            return;
        }
        PlayerAppearance appearance = event.appearance();
        String skinId = appearance == null ? null : appearance.getSkinId();
        if (skinId != null && !skinId.isEmpty()) {
            targetPlayerId = playerId;
        }
    }

    /** True while ReplayMod owns an active replay handler, including before the camera swap. */
    private static boolean isPlaybackActive() {
        resolveReflection();
        if (!replayModPresent) {
            return false;
        }
        try {
            Object replayMod = replayModInstanceField.get(null);
            return replayMod != null && replayHandlerMethod.invoke(replayMod) != null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return false;
        }
    }

    @Nullable
    private static UUID localPlayerId() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null || minecraft.player == null ? null : minecraft.player.getUUID();
    }

    private static void resolveReflection() {
        if (reflectionResolved) {
            return;
        }
        resolveReflectionOnce();
    }

    private static synchronized void resolveReflectionOnce() {
        if (reflectionResolved) {
            return;
        }
        try {
            Class<?> replayMod = Class.forName(REPLAY_MOD_CLASS);
            replayModInstanceField = replayMod.getField("instance");
            replayHandlerMethod = replayMod.getMethod("getReplayHandler");
            try {
                cameraEntityClass = Class.forName(CAMERA_ENTITY_CLASS);
            } catch (ClassNotFoundException | LinkageError absent) {
                cameraEntityClass = null;
            }
            replayModPresent = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
            replayModInstanceField = null;
            replayHandlerMethod = null;
            cameraEntityClass = null;
            replayModPresent = false;
        }
        reflectionResolved = true;
    }
}
