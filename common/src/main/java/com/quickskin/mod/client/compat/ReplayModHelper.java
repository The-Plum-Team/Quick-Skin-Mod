package com.quickskin.mod.client.compat;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.common.event.InternalEventBus;
import com.quickskin.mod.common.event.PlayerAppearanceUpdateEvent;
import com.quickskin.mod.platform.PlatformHelper;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;

import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Compatibility integration for ReplayMod playback.
 *
 * <p>During playback ReplayMod replaces the local player with its own {@code CameraEntity}, so the
 * recorded player is an ordinary remote {@link AbstractClientPlayer} in the replay level. Quick
 * Skin's own client receivers still see the recorded appearance payloads as ReplayMod feeds the
 * recording through the normal packet listener, but nothing re-drives the renderer for a player
 * that never "joins". This helper watches the replay for the recorded player and re-applies the
 * appearance Quick Skin already resolved for that UUID.</p>
 *
 * <p>Every ReplayMod type is reached reflectively and every entry point degrades to the normal
 * skin/cape path when ReplayMod is absent, so a missing or renamed replay class can never break
 * mod initialization.</p>
 */
@Environment(EnvType.CLIENT)
public final class ReplayModHelper {
    private static final String REPLAY_MOD_ID = "replaymod";
    private static final String REPLAY_MOD_REPLAY_CLASS = "com.replaymod.replay.ReplayModReplay";
    private static final String CAMERA_ENTITY_CLASS = "com.replaymod.replay.camera.CameraEntity";

    /**
     * The watcher stops once a started replay has ended, so a forgotten reset can never leave a
     * per-frame task queued for the rest of the session.
     */
    private static final Object WATCHER_LOCK = new Object();

    private static final AtomicBoolean WATCHER_ACTIVE = new AtomicBoolean();
    private static final AtomicInteger INTERCEPTED_PAYLOADS = new AtomicInteger();

    private static boolean modAvailable;
    private static boolean modChecked;
    private static boolean reflectionInitialized;

    @Nullable
    private static Field replayModInstanceField;
    @Nullable
    private static Method replayHandlerAccessor;

    @Nullable
    private static InternalEventBus.Subscription subscription;
    @Nullable
    private static volatile String appliedSkinId;

    private static volatile boolean sawReplay;
    private static volatile boolean skinApplied;

    private ReplayModHelper() {
    }

    /** Checks whether ReplayMod is installed. */
    public static boolean isAvailable() {
        if (!modChecked) {
            checkAvailability();
        }
        return modAvailable;
    }

    private static synchronized void checkAvailability() {
        modChecked = true;

        if (PlatformHelper.isModLoaded(REPLAY_MOD_ID)) {
            modAvailable = true;
            return;
        }

        // Fallback class-based detection for loaders that do not expose the id we expect.
        try {
            Class.forName(REPLAY_MOD_REPLAY_CLASS);
            modAvailable = true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            // ReplayMod not detected; every entry point stays inert.
        }
    }

    /**
     * Reports whether ReplayMod is currently playing a replay back.
     *
     * <p>The authoritative signal is ReplayMod's own replay handler. When that is unavailable the
     * local player being ReplayMod's camera entity is the fallback evidence; both are absent for an
     * ordinary session, so this is {@code false} whenever ReplayMod is not driving the client.</p>
     */
    public static boolean isInReplay() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null || !isAvailable()) {
            return false;
        }
        Boolean handlerActive = replayHandlerActive();
        boolean inReplay = handlerActive != null ? handlerActive : isCameraEntity(minecraft.player);
        if (inReplay) {
            sawReplay = true;
        }
        return inReplay;
    }

    /**
     * Returns the UUID of the recorded player the replay is about, never ReplayMod's camera entity.
     * A recorded player that already carries a Quick Skin appearance wins over an unknown one.
     */
    @Nullable
    public static UUID getTargetPlayerUUID() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            return null;
        }
        UUID fallback = null;
        for (AbstractClientPlayer player : minecraft.level.players()) {
            if (player == null || player == minecraft.player || isCameraEntity(player)) {
                continue;
            }
            UUID playerId = player.getUUID();
            if (playerId == null) {
                continue;
            }
            if (hasResolvedSkin(playerId)) {
                return playerId;
            }
            if (fallback == null) {
                fallback = playerId;
            }
        }
        return fallback;
    }

    /** Resolves a recorded player in the current replay level. */
    @Nullable
    public static AbstractClientPlayer getPlayerByUUID(@Nullable UUID playerId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (playerId == null || minecraft == null || minecraft.level == null) {
            return null;
        }
        return minecraft.level.getPlayerByUUID(playerId) instanceof AbstractClientPlayer player
                ? player
                : null;
    }

    /**
     * Starts the bounded watcher that keeps the recorded player's renderer aligned with the
     * appearance Quick Skin resolved from the recording. Calling this twice is a no-op.
     */
    public static void startReplayPlayerWatcher() {
        synchronized (WATCHER_LOCK) {
            if (!WATCHER_ACTIVE.compareAndSet(false, true)) {
                return;
            }
            subscription = InternalEventBus.getInstance().register(
                    PlayerAppearanceUpdateEvent.class, ReplayModHelper::onAppearanceUpdate);
        }
        scheduleWatcherPass();
    }

    /** Number of Quick Skin appearance payloads observed while the replay was playing. */
    public static int getInterceptedPacketCount() {
        return INTERCEPTED_PAYLOADS.get();
    }

    /** Whether the recorded player's Quick Skin look has been resolved and pushed to the renderer. */
    public static boolean hasSkinBeenApplied() {
        return skinApplied;
    }

    /**
     * Stops the watcher and clears the observation counters.
     *
     * <p>This is the only supported way to reuse the helper for another replay, so a stale count or
     * a stale applied flag can never be read as evidence for a different recording.</p>
     */
    public static void resetReplayEvidenceState() {
        InternalEventBus.Subscription active;
        synchronized (WATCHER_LOCK) {
            WATCHER_ACTIVE.set(false);
            active = subscription;
            subscription = null;
        }
        if (active != null) {
            active.close();
        }
        INTERCEPTED_PAYLOADS.set(0);
        appliedSkinId = null;
        skinApplied = false;
        sawReplay = false;
    }

    private static void onAppearanceUpdate(PlayerAppearanceUpdateEvent event) {
        if (!WATCHER_ACTIVE.get() || !isInReplay()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.player != null
                && minecraft.player.getUUID().equals(event.playerId())) {
            // ReplayMod's camera entity is not a recorded participant.
            return;
        }
        INTERCEPTED_PAYLOADS.incrementAndGet();
    }

    private static void scheduleWatcherPass() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || !WATCHER_ACTIVE.get()) {
            return;
        }
        minecraft.execute(ReplayModHelper::watcherPass);
    }

    private static void watcherPass() {
        if (!WATCHER_ACTIVE.get()) {
            return;
        }
        try {
            if (isInReplay()) {
                applyRecordedAppearance();
            } else if (sawReplay) {
                // The replay ended; stop rescheduling instead of polling for the rest of the session.
                WATCHER_ACTIVE.set(false);
                return;
            }
        } catch (RuntimeException | LinkageError error) {
            WATCHER_ACTIVE.set(false);
            QuickSkin.LOGGER.debug("QuickSkin ReplayMod watcher stopped after a failure", error);
            return;
        }
        scheduleWatcherPass();
    }

    private static void applyRecordedAppearance() {
        UUID target = getTargetPlayerUUID();
        if (target == null) {
            return;
        }
        PlayerAppearanceService service = PlayerAppearanceService.getInstance();
        PlayerAppearance appearance = service.getAppearance(target);
        String skinId = appearance == null ? null : appearance.getSkinId();
        if (skinId == null || skinId.isEmpty() || service.getSkinLocation(target) == null) {
            return;
        }
        // Only re-drive the renderer when the recorded look actually changed.
        if (!skinId.equals(appliedSkinId)) {
            appliedSkinId = skinId;
            service.refreshPlayerRenderer(target);
        }
        skinApplied = true;
    }

    private static boolean hasResolvedSkin(UUID playerId) {
        PlayerAppearance appearance = PlayerAppearanceService.getInstance().getAppearance(playerId);
        String skinId = appearance == null ? null : appearance.getSkinId();
        return skinId != null && !skinId.isEmpty();
    }

    /**
     * @return {@code true}/{@code false} from ReplayMod's own handler, or {@code null} when that
     *         handler cannot be reached and the caller must fall back to camera-entity evidence.
     */
    @Nullable
    private static Boolean replayHandlerActive() {
        initializeReflection();
        Field instanceField = replayModInstanceField;
        Method accessor = replayHandlerAccessor;
        if (instanceField == null || accessor == null) {
            return null;
        }
        try {
            Object replayMod = instanceField.get(null);
            return replayMod == null ? Boolean.FALSE : accessor.invoke(replayMod) != null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            return null;
        }
    }

    private static synchronized void initializeReflection() {
        if (reflectionInitialized) {
            return;
        }
        reflectionInitialized = true;
        if (!isAvailable()) {
            return;
        }
        try {
            Class<?> replayModReplay = Class.forName(REPLAY_MOD_REPLAY_CLASS);
            Field instanceField = replayModReplay.getField("instance");
            Method accessor = replayModReplay.getMethod("getReplayHandler");
            instanceField.setAccessible(true);
            accessor.setAccessible(true);
            replayModInstanceField = instanceField;
            replayHandlerAccessor = accessor;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            // A renamed handler is not fatal; camera-entity detection remains.
            QuickSkin.LOGGER.debug("QuickSkin could not bind ReplayMod's replay handler", error);
        }
    }

    private static boolean isCameraEntity(@Nullable Entity entity) {
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
