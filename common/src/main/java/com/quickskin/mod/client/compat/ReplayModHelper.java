package com.quickskin.mod.client.compat;

import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.networking.NetworkSecurity;
import com.quickskin.mod.networking.payloads.SyncAppearancePayload;
import com.quickskin.mod.networking.payloads.SyncAppearanceV2Payload;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Compatibility integration for ReplayMod playback.
 *
 * <p>ReplayMod replays a recorded packet stream over its own client connection. That connection
 * never performs Quick Skin's hello/ack exchange, so {@code ClientNetworkHandler} correctly refuses
 * the replayed appearance payloads: a replayed stream is not a negotiated live session and must
 * never be able to borrow authority from one. This helper therefore observes the replay connection
 * separately, treats every recorded field as untrusted, and applies the recorded look purely
 * locally through {@link PlayerAppearanceService#applyLookFromNetwork}.</p>
 *
 * <p>Everything here is optional and fails soft. When ReplayMod is absent, when its playback API
 * moves, or when the replay connection exposes no inspectable pipeline, every accessor degrades to
 * "nothing recorded" and the ordinary skin/cape path is untouched.</p>
 */
@Environment(EnvType.CLIENT)
public final class ReplayModHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReplayModHelper.class);

    private static final String REPLAY_MOD_CLASS = "com.replaymod.replay.ReplayModReplay";
    private static final String CAMERA_ENTITY_CLASS = "com.replaymod.replay.camera.CameraEntity";
    private static final String PROBE_NAME = "quickskin_replay_probe";

    /** Recorded sessions are tiny; these caps keep a malformed replay file bounded. */
    private static final int MAX_TRACKED_PLAYERS = 16;
    private static final int MAX_COUNTED_PAYLOADS = 4096;

    private static final Map<UUID, RecordedLook> RECORDED_LOOKS = new ConcurrentHashMap<>();
    private static final AtomicInteger INTERCEPTED_PAYLOADS = new AtomicInteger();

    private static volatile boolean watching;
    private static volatile boolean skinApplied;
    private static volatile UUID targetPlayerId;

    private static volatile Boolean modAvailable;
    private static volatile Class<?> cameraEntityClass;
    private static volatile boolean cameraEntityResolved;
    private static volatile boolean probeWarningLogged;

    private ReplayModHelper() {
    }

    /** Checks if ReplayMod is installed. */
    public static boolean isAvailable() {
        Boolean cached = modAvailable;
        if (cached == null) {
            cached = resolve(REPLAY_MOD_CLASS) != null;
            modAvailable = cached;
        }
        return cached;
    }

    /**
     * Reports whether ReplayMod is currently playing a recording back.
     *
     * <p>This is the client's poll point during playback, so it also arms the payload probe as
     * early as the replay connection exists and drains any look that has already been recorded.</p>
     */
    public static boolean isInReplay() {
        if (!replayActive()) {
            return false;
        }
        installProbe();
        applyRecordedLooks();
        return true;
    }

    /** Clears every counter and cached look before a new recording is played back. */
    public static void resetReplayEvidenceState() {
        watching = false;
        skinApplied = false;
        targetPlayerId = null;
        INTERCEPTED_PAYLOADS.set(0);
        RECORDED_LOOKS.clear();
    }

    /**
     * Starts applying recorded Quick Skin looks to the players of the running replay.
     *
     * <p>Idempotent: the probe is installed at most once per replay connection.</p>
     */
    public static void startReplayPlayerWatcher() {
        if (!replayActive()) {
            return;
        }
        watching = true;
        installProbe();
        applyRecordedLooks();
    }

    /** The recorded player the replay is about, or {@code null} while none has been seen. */
    @Nullable
    public static UUID getTargetPlayerUUID() {
        applyRecordedLooks();
        return targetPlayerId;
    }

    /** Resolves a player of the currently loaded level, never ReplayMod's camera stand-in. */
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
        return player instanceof AbstractClientPlayer clientPlayer ? clientPlayer : null;
    }

    /** How many recorded Quick Skin appearance payloads this playback has accepted. */
    public static int getInterceptedPacketCount() {
        return INTERCEPTED_PAYLOADS.get();
    }

    /** Whether a recorded look was applied and its skin texture resolved for the target player. */
    public static boolean hasSkinBeenApplied() {
        applyRecordedLooks();
        return skinApplied;
    }

    /** Pure query: no probe installation, so it is safe from any polling caller. */
    private static boolean replayActive() {
        if (!isAvailable()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null || replayHandler() == null) {
            return false;
        }
        Class<?> camera = cameraEntityClass();
        return camera == null || camera.isInstance(minecraft.player);
    }

    @Nullable
    private static Object replayHandler() {
        Class<?> replay = resolve(REPLAY_MOD_CLASS);
        if (replay == null) {
            return null;
        }
        try {
            Object instance = replay.getField("instance").get(null);
            return instance == null ? null : replay.getMethod("getReplayHandler").invoke(instance);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return null;
        }
    }

    @Nullable
    private static Class<?> cameraEntityClass() {
        if (!cameraEntityResolved) {
            cameraEntityClass = resolve(CAMERA_ENTITY_CLASS);
            cameraEntityResolved = true;
        }
        return cameraEntityClass;
    }

    @Nullable
    private static Class<?> resolve(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException | LinkageError exception) {
            return null;
        }
    }

    /**
     * Inserts the read-only payload probe immediately before the replay connection's own packet
     * handler, which is the only position that observes packets before they are consumed.
     */
    private static void installProbe() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null) {
                return;
            }
            ClientPacketListener listener = minecraft.getConnection();
            if (listener == null) {
                return;
            }
            Connection connection = listener.getConnection();
            if (connection == null) {
                return;
            }
            Channel channel = channelOf(connection);
            if (channel == null || !channel.isOpen()) {
                return;
            }
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(PROBE_NAME) != null) {
                return;
            }
            String owner = null;
            for (Map.Entry<String, ChannelHandler> entry : pipeline.toMap().entrySet()) {
                if (entry.getValue() == connection) {
                    owner = entry.getKey();
                    break;
                }
            }
            if (owner == null) {
                warnOnce("ReplayMod playback exposes no inspectable packet handler");
                return;
            }
            pipeline.addBefore(owner, PROBE_NAME, new RecordedPayloadProbe());
        } catch (Exception | LinkageError exception) {
            warnOnce("could not observe the ReplayMod connection: " + exception);
        }
    }

    @Nullable
    private static Channel channelOf(Connection connection) {
        // Located by field type: field names are remapped differently per loader.
        for (Class<?> type = connection.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Channel.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    if (field.get(connection) instanceof Channel channel) {
                        return channel;
                    }
                } catch (ReflectiveOperationException | RuntimeException exception) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Runs on a Netty thread; it only validates and stores, never touching game state. */
    private static void observe(Object message) {
        if (!(message instanceof ClientboundCustomPayloadPacket packet)) {
            return;
        }
        CustomPacketPayload payload = packet.payload();
        if (payload instanceof SyncAppearanceV2Payload v2) {
            record(v2.playerId(), v2.skinId(), v2.capeId(), v2.model(), true);
        } else if (payload instanceof SyncAppearancePayload v1) {
            record(v1.playerId(), v1.skinId(), v1.capeId(), v1.model(), false);
        }
    }

    private static void record(
            @Nullable UUID playerId, @Nullable String skinId,
            @Nullable String capeId, @Nullable String model, boolean strongIdentities) {
        if (playerId == null || !NetworkSecurity.isValidModel(model)) {
            return;
        }
        boolean validIdentities = strongIdentities
                ? NetworkSecurity.isValidV2AppearanceId(skinId, "skin")
                        && NetworkSecurity.isValidV2AppearanceId(capeId, "cape")
                : NetworkSecurity.isValidLegacyAppearanceId(skinId, "skin")
                        && NetworkSecurity.isValidLegacyAppearanceId(capeId, "cape");
        if (!validIdentities) {
            return;
        }
        if (!RECORDED_LOOKS.containsKey(playerId) && RECORDED_LOOKS.size() >= MAX_TRACKED_PLAYERS) {
            return;
        }
        RECORDED_LOOKS.put(playerId, new RecordedLook(skinId, capeId, model));
        INTERCEPTED_PAYLOADS.updateAndGet(
                counted -> counted >= MAX_COUNTED_PAYLOADS ? counted : counted + 1);
    }

    /** Commits recorded looks on the client main thread, exactly like an ordinary S2C update. */
    private static void applyRecordedLooks() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!watching || minecraft == null || !minecraft.isSameThread()
                || minecraft.level == null || RECORDED_LOOKS.isEmpty()) {
            return;
        }
        UUID cameraId = minecraft.player == null ? null : minecraft.player.getUUID();
        PlayerAppearanceService appearances = PlayerAppearanceService.getInstance();
        UUID selected = null;
        for (Map.Entry<UUID, RecordedLook> entry : RECORDED_LOOKS.entrySet()) {
            UUID playerId = entry.getKey();
            if (playerId.equals(cameraId)) {
                continue;
            }
            RecordedLook look = entry.getValue();
            PlayerAppearance current = appearances.getAppearance(playerId);
            if (current == null
                    || !Objects.equals(current.getSkinId(), look.skinId())
                    || !Objects.equals(current.getCapeId(), look.capeId())) {
                appearances.applyLookFromNetwork(
                        playerId, look.skinId(), look.capeId(), look.model());
            } else {
                appearances.refreshPlayerRenderer(playerId);
            }
            // A recorded player that already exists in the level wins over one still spawning.
            if (selected == null || (getPlayerByUUID(playerId) != null
                    && getPlayerByUUID(selected) == null)) {
                selected = playerId;
            }
        }
        targetPlayerId = selected;
        skinApplied = selected != null && isSkinResolved(appearances, selected);
    }

    private static boolean isSkinResolved(PlayerAppearanceService appearances, UUID playerId) {
        PlayerAppearance applied = appearances.getAppearance(playerId);
        return applied != null
                && applied.getSkinId() != null
                && !applied.getSkinId().isEmpty()
                && appearances.getSkinLocation(playerId) != null;
    }

    private static void warnOnce(String message) {
        if (!probeWarningLogged) {
            probeWarningLogged = true;
            LOGGER.warn("QuickSkin ReplayMod compatibility degraded: {}", message);
        }
    }

    private record RecordedLook(String skinId, String capeId, String model) {
    }

    private static final class RecordedPayloadProbe extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
            try {
                observe(message);
            } catch (Exception | LinkageError exception) {
                warnOnce("could not read a recorded payload: " + exception);
            }
            context.fireChannelRead(message);
        }
    }
}
