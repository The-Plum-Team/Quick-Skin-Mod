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

import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Optional compatibility bridge for ReplayMod playback.
 *
 * <p>During playback the local entity is ReplayMod's camera, and the recorded player is spawned
 * from the replay stream. Quick Skin's recorded appearance payloads are re-delivered through the
 * ordinary client path, but they frequently arrive before the recorded player entity exists, so
 * {@code refreshPlayerRenderer} finds nothing to mark dirty and the renderer keeps the vanilla
 * texture. The watcher below re-asserts the already stored appearance once that entity is present
 * and stops as soon as it succeeds.</p>
 *
 * <p>Every ReplayMod lookup is reflective and guarded: without ReplayMod, or against an
 * incompatible ReplayMod shape, each entry point degrades to a no-op and the normal skin/cape path
 * is untouched. All Minecraft state is read and committed on the client main thread, and the
 * waiting loop spends a bounded number of polls.</p>
 */
@Environment(EnvType.CLIENT)
public final class ReplayModHelper {
    private static final String REPLAY_MOD_ID = "replaymod";
    private static final String REPLAY_CLASS = "com.replaymod.replay.ReplayModReplay";

    /** Bounded main-thread polls the watcher may spend waiting for the recorded player. */
    private static final int MAX_WATCHER_POLLS = 20 * 120;

    private static volatile boolean checked;
    private static volatile boolean modAvailable;
    private static volatile boolean replayShapeUnavailable;
    private static volatile Class<?> replayClass;
    private static volatile Method replayHandlerMethod;

    private static final AtomicInteger INTERCEPTED = new AtomicInteger();
    private static final AtomicBoolean APPLIED = new AtomicBoolean();
    private static final AtomicReference<UUID> TARGET = new AtomicReference<>();
    private static final AtomicReference<InternalEventBus.Subscription> WATCHER =
            new AtomicReference<>();
    private static final AtomicBoolean POLL_SCHEDULED = new AtomicBoolean();
    private static final AtomicInteger REMAINING_POLLS = new AtomicInteger();

    private ReplayModHelper() {
    }

    /**
     * Checks whether ReplayMod is installed.
     */
    public static boolean isAvailable() {
        if (!checked) {
            checkAvailability();
        }
        return modAvailable;
    }

    private static synchronized void checkAvailability() {
        if (checked) {
            return;
        }
        boolean available = false;
        try {
            available = PlatformHelper.isModLoaded(REPLAY_MOD_ID);
        } catch (RuntimeException | LinkageError | AssertionError ignored) {
            // Loader metadata is unavailable here; fall back to class detection.
        }
        if (!available) {
            try {
                Class.forName(REPLAY_CLASS);
                available = true;
            } catch (ClassNotFoundException | LinkageError ignored) {
                // ReplayMod is simply not present.
            }
        }
        modAvailable = available;
        checked = true;
    }

    /**
     * Returns true only while ReplayMod is actively playing a replay back.
     */
    public static boolean isInReplay() {
        return replayHandler() != null;
    }

    /**
     * Arms the idempotent watcher that re-asserts a recorded Quick Skin appearance on the recorded
     * player. Does nothing when ReplayMod is absent.
     */
    public static void startReplayPlayerWatcher() {
        if (!isAvailable() || WATCHER.get() != null) {
            return;
        }
        InternalEventBus.Subscription subscription = InternalEventBus.getInstance()
                .register(PlayerAppearanceUpdateEvent.class, ReplayModHelper::onAppearanceUpdate);
        if (!WATCHER.compareAndSet(null, subscription)) {
            subscription.close();
            return;
        }
        REMAINING_POLLS.set(MAX_WATCHER_POLLS);
        scheduleReassert();
    }

    /**
     * Releases the watcher subscription and its remaining poll budget.
     */
    public static void stopReplayPlayerWatcher() {
        InternalEventBus.Subscription subscription = WATCHER.getAndSet(null);
        REMAINING_POLLS.set(0);
        if (subscription != null) {
            subscription.close();
        }
    }

    /**
     * Clears the observed playback evidence and stops the watcher, so a new playback starts from a
     * known state.
     */
    public static void resetReplayEvidenceState() {
        stopReplayPlayerWatcher();
        INTERCEPTED.set(0);
        APPLIED.set(false);
        TARGET.set(null);
    }

    /**
     * Number of recorded Quick Skin appearance payloads observed since the last reset.
     */
    public static int getInterceptedPacketCount() {
        return INTERCEPTED.get();
    }

    /**
     * True once the recorded player's stored Quick Skin appearance has been re-asserted onto the
     * live replay renderer.
     */
    public static boolean hasSkinBeenApplied() {
        return APPLIED.get();
    }

    /**
     * The recorded player being played back, never ReplayMod's camera entity.
     */
    @Nullable
    public static UUID getTargetPlayerUUID() {
        UUID recorded = TARGET.get();
        if (recorded != null) {
            return recorded;
        }
        if (!isInReplay()) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            return null;
        }
        UUID camera = minecraft.player == null ? null : minecraft.player.getUUID();
        UUID candidate = null;
        try {
            for (Object entry : minecraft.level.players()) {
                if (!(entry instanceof AbstractClientPlayer player)) {
                    continue;
                }
                UUID playerId = player.getUUID();
                if (playerId == null || playerId.equals(camera)) {
                    continue;
                }
                // Deterministic selection keeps repeated polls stable while the replay spawns
                // entities.
                if (candidate == null || playerId.compareTo(candidate) < 0) {
                    candidate = playerId;
                }
            }
        } catch (RuntimeException | LinkageError exception) {
            return null;
        }
        if (candidate != null) {
            TARGET.compareAndSet(null, candidate);
        }
        return candidate;
    }

    /**
     * Resolves a client player entity by exact UUID, or null when it is not currently spawned.
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
        try {
            return minecraft.level.getPlayerByUUID(playerId) instanceof AbstractClientPlayer player
                    ? player
                    : null;
        } catch (RuntimeException | LinkageError exception) {
            return null;
        }
    }

    private static void onAppearanceUpdate(PlayerAppearanceUpdateEvent event) {
        if (WATCHER.get() == null || !isInReplay()) {
            return;
        }
        UUID playerId = event.playerId();
        Minecraft minecraft = Minecraft.getInstance();
        UUID camera = minecraft == null || minecraft.player == null
                ? null : minecraft.player.getUUID();
        if (playerId.equals(camera)) {
            // The replay camera is not a recorded player and carries no recorded appearance.
            return;
        }
        PlayerAppearance appearance = event.appearance();
        String skinId = appearance.getSkinId();
        if (skinId == null || skinId.isEmpty()) {
            return;
        }
        INTERCEPTED.incrementAndGet();
        TARGET.set(playerId);
        // A seek can respawn the recorded player, so a later payload re-arms the bounded wait.
        // The applied flag stays monotonic for this playback and is cleared only by an explicit
        // reset, so a late payload cannot retract evidence a caller has already observed.
        REMAINING_POLLS.set(MAX_WATCHER_POLLS);
        scheduleReassert();
    }

    private static void scheduleReassert() {
        if (WATCHER.get() == null || REMAINING_POLLS.get() <= 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        if (!POLL_SCHEDULED.compareAndSet(false, true)) {
            return;
        }
        try {
            minecraft.execute(ReplayModHelper::reassertRecordedAppearance);
        } catch (RuntimeException | LinkageError exception) {
            POLL_SCHEDULED.set(false);
        }
    }

    private static void reassertRecordedAppearance() {
        POLL_SCHEDULED.set(false);
        if (WATCHER.get() == null) {
            return;
        }
        if (REMAINING_POLLS.decrementAndGet() <= 0) {
            QuickSkin.LOGGER.debug(
                    "Quick Skin stopped waiting for a recorded ReplayMod player after {} polls",
                    MAX_WATCHER_POLLS);
            stopReplayPlayerWatcher();
            return;
        }
        if (!isInReplay()) {
            scheduleReassert();
            return;
        }
        UUID target = getTargetPlayerUUID();
        if (getPlayerByUUID(target) == null) {
            scheduleReassert();
            return;
        }
        PlayerAppearanceService appearances = PlayerAppearanceService.getInstance();
        if (appearances.getSkinLocation(target) == null) {
            // The recorded texture payload has not been resolved yet; keep waiting.
            scheduleReassert();
            return;
        }
        appearances.refreshPlayerRenderer(target);
        if (APPLIED.compareAndSet(false, true)) {
            QuickSkin.LOGGER.debug(
                    "Quick Skin re-applied the recorded appearance for {} during ReplayMod playback",
                    target);
        }
        // Settled: the next recorded payload re-arms the watcher instead of polling on.
    }

    @Nullable
    private static Object replayHandler() {
        if (!isAvailable() || replayShapeUnavailable) {
            return null;
        }
        try {
            Class<?> replay = replayClass;
            if (replay == null) {
                replay = Class.forName(REPLAY_CLASS);
                replayClass = replay;
            }
            Method handler = replayHandlerMethod;
            if (handler == null) {
                handler = replay.getMethod("getReplayHandler");
                replayHandlerMethod = handler;
            }
            Object instance = replay.getField("instance").get(null);
            return instance == null ? null : handler.invoke(instance);
        } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException exception) {
            replayShapeUnavailable = true;
            QuickSkin.LOGGER.debug(
                    "ReplayMod playback detection is unavailable: {}", exception.toString());
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            return null;
        }
    }
}
