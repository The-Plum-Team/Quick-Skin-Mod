package com.quickskin.mod.networking;

import com.quickskin.mod.config.ServerConfig;
import com.quickskin.mod.networking.protocol.ProtocolCapability;
import com.quickskin.mod.networking.protocol.ProtocolProfile;
//? if >=1.20.5 {
import com.quickskin.mod.networking.payloads.SyncImmersivePosePayload;
//?}
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * The immersive poses of the connected players (ADR 0019). A player's client says which of the
 * skin menu's poses its player plays while that menu floats round them, and the server tells the
 * other Quick Skin players who see that player, whose clients play it on the player in their own
 * drawing. Nothing about the player changes on the server: not their position, their hitbox nor
 * what players without Quick Skin see.
 *
 * <p>Only the clients the game's entity tracking pairs with the player are told
 * ({@link #bindViewers}, {@link ImmersivePoseAudience}): a client that starts to see a posing
 * player is told the pose as already under way, and one that stops seeing them, or the whole
 * server when the pose ends, is told it ended. A pose ends when its client says so, when the
 * player leaves, dies, respawns, changes world or becomes a spectator, when the client has not
 * repeated it in time, and for everyone while {@code allowImmersivePoses} is off. Server thread
 * only, except {@link #admits}.
 */
public final class ServerImmersivePoses {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerImmersivePoses.class);
    /** How often stale poses are looked for, in ticks. */
    private static final int EXPIRY_INTERVAL_TICKS = 20;

    private static final ImmersivePoseRelay RELAY = new ImmersivePoseRelay();

    /** What a recipient was told as: its exact session, player entity and world. */
    private record Viewer(Object connection, ServerPlayer player, Level level) {
        static Viewer of(ServerPlayer player) {
            return new Viewer(player.connection, player, player.level());
        }
    }

    /** A posing player: the exact session, player entity and world its pose was told in, and who was told. */
    private record Place(Object connection, ServerPlayer player, Level level,
                         ImmersivePoseAudience<UUID, Viewer> audience) {
    }

    private static final Map<UUID, Place> PLACES = new HashMap<>();
    /** The players whose clients the game pairs with a player's entity; none until bound. */
    private static volatile Function<ServerPlayer, Collection<ServerPlayer>> viewers = player -> List.of();

    private ServerImmersivePoses() {
    }

    /**
     * Names who sees a player: the players whose connections the game's entity tracking pairs with
     * that player's entity. The composition root binds it, since the tracker is not visible here.
     */
    public static void bindViewers(Function<ServerPlayer, Collection<ServerPlayer>> source) {
        viewers = Objects.requireNonNull(source, "source");
    }

    /** Whether this exact session negotiated {@code immersive-poses}, so it takes the pose packets. */
    static boolean canReceive(ServerPlayer player) {
        if (player == null || !ProtocolNetwork.isReady(player)) return false;
        ProtocolProfile profile = ProtocolNetwork.profile(player);
        return ProtocolNetwork.acceptsV2(profile) && profile.supports(ProtocolCapability.IMMERSIVE_POSES);
    }

    /** Network thread: whether this exact session may tell the pose of this player. */
    static boolean admits(ServerPlayer sender, UUID playerId) {
        return sender != null && admits(ProtocolNetwork.profile(sender), sender.getUUID(), playerId);
    }

    /**
     * A pose is admitted only from a negotiated v2 session with {@code immersive-poses}, and only
     * about the sender itself: a client never poses another player.
     */
    static boolean admits(ProtocolProfile senderProfile, UUID senderId, UUID playerId) {
        return senderProfile != null
                && ProtocolNetwork.acceptsV2(senderProfile)
                && senderProfile.supports(ProtocolCapability.IMMERSIVE_POSES)
                && senderId != null
                && senderId.equals(playerId);
    }

    private static boolean allowed() {
        return ServerConfig.getInstance().allowImmersivePoses;
    }

    /** Server thread: the player's client says what pose its player plays now. */
    static void request(ServerPlayer player, int sequence, ImmersivePose pose) {
        if (player == null || !allowed()) return;
        // A player who cannot be seen standing plays no pose for anyone
        ImmersivePose shown = player.isAlive() && !player.isSpectator() ? pose : ImmersivePose.OFF;
        ImmersivePoseRelay.Outcome outcome = RELAY.accept(
                player.getUUID(), player.connection, sequence, shown, System.nanoTime());
        if (outcome != ImmersivePoseRelay.Outcome.RELAY) return;
        LOGGER.debug("{} plays immersive pose {}", player.getScoreboardName(), shown);
        UUID playerId = player.getUUID();
        Place place = PLACES.get(playerId);
        if (!shown.active()) {
            if (place != null) end(playerId);
            return;
        }
        if (place == null || place.player() != player || place.connection() != player.connection
                || place.level() != player.level()) {
            if (place != null) tellEnded(playerId, place.audience().end());
            place = new Place(player.connection, player, player.level(), new ImmersivePoseAudience<>());
            PLACES.put(playerId, place);
        }
        ImmersivePoseAudience.Update<Viewer> update = place.audience().change(seeing(player));
        for (Viewer viewer : update.joined()) send(viewer.player(), playerId, shown);
        tellEnded(playerId, update.left());
    }

    /** A client's session became ready: it is told the poses of the players it sees. */
    static void sessionReady(ServerPlayer recipient) {
        if (!canReceive(recipient) || PLACES.isEmpty()) return;
        follow();
    }

    /**
     * Server thread, once a tick: ends the poses of players who died, respawned, changed world or
     * went quiet, and tells the clients that started to see a posing player, or stopped, so.
     */
    static void tick(MinecraftServer server) {
        if (PLACES.isEmpty()) return;
        if (!allowed()) {
            for (UUID playerId : List.copyOf(PLACES.keySet())) {
                RELAY.end(playerId);
                end(playerId);
            }
            return;
        }
        for (Iterator<Map.Entry<UUID, Place>> it = PLACES.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Place> entry = it.next();
            Place place = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != place.player() || player.connection != place.connection() || !player.isAlive()
                    || player.isSpectator() || player.level() != place.level()) {
                it.remove();
                RELAY.end(entry.getKey());
                tellEnded(entry.getKey(), place.audience().end());
            }
        }
        if (server.getTickCount() % EXPIRY_INTERVAL_TICKS == 0) {
            for (UUID playerId : RELAY.expire(System.nanoTime())) end(playerId);
        }
        follow();
    }

    /** Tells each posing player's pose to the clients that started to see them, and its end to those that stopped. */
    private static void follow() {
        for (Map.Entry<UUID, Place> entry : PLACES.entrySet()) {
            ImmersivePose pose = RELAY.current(entry.getKey());
            if (!pose.active()) continue;
            ImmersivePoseAudience.Update<Viewer> update = entry.getValue().audience().follow(seeing(entry.getValue().player()));
            if (update.isEmpty()) continue;
            ImmersivePose underWay = pose.with(ImmersivePose.SETTLED);
            for (Viewer viewer : update.joined()) send(viewer.player(), entry.getKey(), underWay);
            tellEnded(entry.getKey(), update.left());
        }
    }

    /** The exact session ended: its pose ends for everyone and it is told nothing more. */
    static void playerLeft(UUID playerId, Object connection) {
        Place place = PLACES.get(playerId);
        RELAY.remove(playerId, connection);
        if (place != null && place.connection() == connection) end(playerId);
        for (Place other : PLACES.values()) {
            other.audience().forget(playerId, viewer -> viewer.connection() == connection);
        }
    }

    static void clear() {
        RELAY.clear();
        PLACES.clear();
    }

    private static void end(UUID playerId) {
        Place place = PLACES.remove(playerId);
        if (place != null) tellEnded(playerId, place.audience().end());
    }

    /**
     * The Quick Skin clients other than the player's own that the game pairs with the player now,
     * by player, as what they are told as.
     */
    private static Map<UUID, Viewer> seeing(ServerPlayer player) {
        Collection<ServerPlayer> paired;
        try {
            paired = viewers.apply(player);
        } catch (RuntimeException | LinkageError unavailable) {
            LOGGER.debug("Could not read who sees {}", player.getScoreboardName(), unavailable);
            paired = List.of();
        }
        Map<UUID, Viewer> seeing = new LinkedHashMap<>();
        for (ServerPlayer viewer : paired) {
            if (viewer == null || viewer == player || viewer.getUUID().equals(player.getUUID())
                    || viewer.level() != player.level() || !canReceive(viewer)) continue;
            seeing.put(viewer.getUUID(), Viewer.of(viewer));
        }
        return seeing;
    }

    /** Tells the clients in {@code ended}, where still the same session, that {@code subject}'s pose ended for them. */
    private static void tellEnded(UUID subject, List<Viewer> ended) {
        for (Viewer viewer : ended) {
            if (viewer.player().connection == viewer.connection()) {
                send(viewer.player(), subject, ImmersivePose.OFF);
            }
        }
    }

    private static void send(ServerPlayer recipient, UUID subject, ImmersivePose pose) {
        try {
            //? if <1.20.5 {
            NetworkTransport.INSTANCE.sendImmersivePoseToPlayer(recipient, subject, pose);
            //?} else {
            NetworkTransport.INSTANCE.sendToPlayer(recipient, new SyncImmersivePosePayload(subject, pose));
            //?}
        } catch (RuntimeException error) {
            LOGGER.debug("Could not send an immersive pose to {}", recipient.getScoreboardName(), error);
        }
    }
}
