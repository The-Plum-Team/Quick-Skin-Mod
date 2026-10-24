package com.quickskin.mod.networking;

import com.quickskin.mod.common.data.ContentId;
import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.common.util.SkinModelDetector;
import com.quickskin.mod.networking.protocol.ProtocolCapability;
import com.quickskin.mod.networking.protocol.ProtocolProfile;
import com.quickskin.mod.server.concurrent.ServerCacheIoExecutor;
import com.quickskin.mod.server.data.ServerPlayerAppearanceRepository;
import com.quickskin.mod.server.data.ServerSkinAssignmentStore;
import com.quickskin.mod.server.data.SkinPoolFolder;
import com.quickskin.mod.server.storage.ServerTextureCache;
//? if >=1.21 {
import com.quickskin.mod.networking.payloads.AssignedSkinPayload;
import com.quickskin.mod.networking.payloads.SkinAdminResultPayload;
import com.quickskin.mod.networking.payloads.SkinAdminStatePayload;
//?}
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Server side of assigned skins (ADR 0016), on the server thread: admins give players a skin for
 * now (one they choose, one from the world's skin pool, or each other's own), which every other
 * Quick Skin client sees instead of the player's own until an admin takes it back.
 *
 * <p>The player's own appearance stays in the repository untouched, so their client's uploads
 * and acknowledgements work as always: {@link #shownToOthers} swaps the skin and model only in
 * what other players are sent, and the player's own client learns of the skin through
 * {@code assigned_skin} and shows it on itself. The skin of a connected player is pinned in the
 * texture cache under a holder of its own, against the same budget as players' appearances, so
 * the cache never evicts it; like other appearances of players who left, that of an absent player
 * pins nothing, and a skin the cache no longer holds when they return is taken back. The pool's
 * skins are pinned under one holder, as many as the budget holds. Admins' clients manage it all
 * with {@code skin_admin} requests and learn the pool and who has a skin from
 * {@code skin_admin_state}; a request that names a skin the admin is still uploading waits for
 * that upload, at most {@link #UPLOAD_WAIT_TICKS}, behind at most {@link #MAX_WAITING} others.</p>
 */
public final class ServerSkinAssignments {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerSkinAssignments.class);
    /** Holds the pins of the pool's skins in the texture cache. */
    private static final UUID POOL_HOLDER =
            UUID.nameUUIDFromBytes("quickskin:skin-pool".getBytes(StandardCharsets.UTF_8));
    /** How long a request waits for the upload of the skin it names. */
    static final int UPLOAD_WAIT_TICKS = 30 * 20;
    /** Requests of one admin that may wait for uploads at once; an older one is answered when more come. */
    static final int MAX_WAITING = 4;
    /** Larger textures of an {@code auto} model are taken for classic rather than decoded on the server thread. */
    private static final int MAX_DETECTED_BYTES = 256 * 1024;
    private static final int STATE_POLL_TICKS = 20;
    /** The requests of each admin that wait for an upload, oldest first. */
    private static final Map<UUID, Deque<PendingRequest>> PENDING = new HashMap<>();
    /** Admins' sessions that were sent the state, by exact connection. */
    private static final Map<UUID, Object> STATE_SENT = new HashMap<>();
    private static boolean stateChanged;
    private static long ticks;

    /** What became of an admin's request: a status, and how many players or skins it touched. */
    public record Outcome(SkinAdminStatus status, int count) {
        static Outcome of(SkinAdminStatus status) {
            return new Outcome(status, 0);
        }
    }

    /** A request from an admin's client, as it arrived. */
    record Request(int requestId, SkinAdminAction action, List<UUID> players, String skinId, String model,
                   String name) {
    }

    private record PendingRequest(Object connection, Request request, long deadline) {
    }

    private ServerSkinAssignments() {
    }

    private static ServerSkinAssignmentStore store() {
        return ServerSkinAssignmentStore.getInstance();
    }

    private static ServerTextureCache textures() {
        return ServerTextureCache.getInstance();
    }

    /**
     * The appearance other players are sent for a player: an assigned skin and model over their
     * own, while the cache holds that skin.
     */
    @Nullable
    public static PlayerAppearance shownToOthers(UUID playerId, @Nullable PlayerAppearance own) {
        ServerSkinAssignmentStore.AssignedSkin skin = store().assigned(playerId);
        if (skin == null || !textures().isSkin(skin.hash())) return own;
        return new PlayerAppearance(playerId, skin.skinId(), own != null ? own.getCapeId() : "", skin.model());
    }

    /**
     * Server start, once the world's textures and the store are loaded: adds the skin folder's
     * PNGs to the pool and pins its skins. Nobody is connected yet, so no assigned skin is pinned.
     */
    public static void start(Path folder) {
        ServerSkinAssignmentStore store = store();
        SkinPoolFolder.createIfMissing(folder);
        List<String> imported = SkinPoolFolder.commit(SkinPoolFolder.prepare(folder), store);
        int pinned = pinPool();
        if (pinned < store.pool().size()) {
            LOGGER.warn("{} of the skin pool's {} skins are not held or not within the server's texture budget",
                    store.pool().size() - pinned, store.pool().size());
        }
        if (!store.pool().isEmpty() || !store.assignedSkins().isEmpty()) {
            LOGGER.info("Quick Skin's skin pool holds {} skins ({} from {}); {} players have an assigned skin",
                    store.pool().size(), imported.size(), folder, store.assignedSkins().size());
        }
    }

    /**
     * Reads the skin folder again, off the server thread, and adds its skins to the pool.
     *
     * @param done told on the server thread how many skins the folder gave, or -1 when it could not run
     */
    public static void reloadFolder(MinecraftServer server, Path folder, Consumer<Integer> done) {
        boolean submitted = ServerCacheIoExecutor.getInstance().submit(() -> {
            List<SkinPoolFolder.Found> found = SkinPoolFolder.prepare(folder);
            server.execute(() -> {
                int count = SkinPoolFolder.commit(found, store()).size();
                pinPool();
                stateChanged = true;
                done.accept(count);
            });
        });
        if (!submitted) done.accept(-1);
    }

    /**
     * A player joined: their assigned skin is pinned again, or taken back when the cache no longer
     * holds it or the texture budget has no room. Runs before their appearance is sent to anyone.
     */
    public static void playerJoined(ServerPlayer player) {
        ServerSkinAssignmentStore.AssignedSkin skin = store().assigned(player.getUUID());
        if (skin == null) return;
        if (!textures().isSkin(skin.hash())) {
            LOGGER.info("Took back the skin assigned to {}: this world no longer holds it", player.getScoreboardName());
        } else if (!textures().tryReplaceSkinPins(holder(player.getUUID()), List.of(skin.hash()))) {
            LOGGER.warn("Took back the skin assigned to {}: the server's texture budget is full", player.getScoreboardName());
        } else {
            return;
        }
        store().lift(player.getUUID());
        stateChanged = true;
    }

    /** A player left (no session of theirs is left): their assigned skin stays, without a pin. */
    public static void playerGone(UUID playerId) {
        textures().releaseAppearancePins(holder(playerId));
    }

    // ----- What admins do -----

    /** Gives these players this skin, which the server must hold, for now. */
    public static Outcome assign(Collection<ServerPlayer> players, String skinId, String model, String admin) {
        if (!ServerSkinAssignmentStore.isValid(skinId, model)) return Outcome.of(SkinAdminStatus.INVALID);
        String hash = skinId.substring("local_skin:".length());
        if (!textures().isSkin(hash)) return Outcome.of(SkinAdminStatus.MISSING_TEXTURE);
        if (players.isEmpty()) return Outcome.of(SkinAdminStatus.NOTHING);
        int changed = 0;
        boolean full = false;
        for (ServerPlayer player : players) {
            int result = give(player, skinId, model);
            if (result > 0) changed++;
            if (result < 0) full = true;
        }
        if (changed > 0) LOGGER.info("{} gave {} players the skin {}", admin, changed, hash);
        if (changed == 0 && full) return Outcome.of(SkinAdminStatus.NO_ROOM);
        return new Outcome(SkinAdminStatus.DONE, changed);
    }

    /**
     * Gives each player a skin from the pool, in random order: all different while the pool has
     * enough, then again from the start. Pool skins the cache no longer holds are left out.
     */
    public static Outcome assignRandom(Collection<ServerPlayer> players, Random random, String admin) {
        List<PoolSkinEntry> pool = new ArrayList<>();
        for (PoolSkinEntry skin : store().pool()) {
            if (textures().isSkin(skin.skinId().substring("local_skin:".length()))) pool.add(skin);
        }
        if (pool.isEmpty()) return Outcome.of(SkinAdminStatus.POOL_EMPTY);
        if (players.isEmpty()) return Outcome.of(SkinAdminStatus.NOTHING);
        java.util.Collections.shuffle(pool, random);
        int changed = 0;
        int index = 0;
        boolean full = false;
        for (ServerPlayer player : players) {
            PoolSkinEntry skin = pool.get(index++ % pool.size());
            int result = give(player, skin.skinId(), skin.model());
            if (result >= 0) changed++;
            else full = true;
        }
        LOGGER.info("{} gave {} players random skins from the pool", admin, changed);
        if (changed == 0 && full) return Outcome.of(SkinAdminStatus.NO_ROOM);
        return new Outcome(SkinAdminStatus.DONE, changed);
    }

    /**
     * Gives the players each other's own Quick Skin skins, so that nobody keeps their own. Players
     * without a Quick Skin skin of their own keep what they have.
     */
    public static Outcome shuffle(Collection<ServerPlayer> players, Random random, String admin) {
        List<ServerPlayer> taking = new ArrayList<>();
        List<String[]> skins = new ArrayList<>();
        for (ServerPlayer player : players) {
            String[] own = ownSkin(player.getUUID());
            if (own != null) {
                taking.add(player);
                skins.add(own);
            }
        }
        if (taking.size() < 2) return Outcome.of(SkinAdminStatus.NOTHING);
        // Sattolo's shuffle makes one cycle through everyone, so nobody keeps their own skin
        int[] order = new int[taking.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        for (int i = order.length - 1; i > 0; i--) {
            int j = random.nextInt(i);
            int swap = order[i];
            order[i] = order[j];
            order[j] = swap;
        }
        int changed = 0;
        boolean full = false;
        for (int i = 0; i < taking.size(); i++) {
            String[] skin = skins.get(order[i]);
            int result = give(taking.get(i), skin[0], skin[1]);
            if (result >= 0) changed++;
            else full = true;
        }
        LOGGER.info("{} swapped the skins of {} players", admin, changed);
        if (changed == 0 && full) return Outcome.of(SkinAdminStatus.NO_ROOM);
        return new Outcome(SkinAdminStatus.DONE, changed);
    }

    /** Takes back the skins admins gave these players, who show their own again. */
    public static Outcome lift(Collection<ServerPlayer> players, String admin) {
        int lifted = 0;
        for (ServerPlayer player : players) {
            if (store().lift(player.getUUID()) != null) {
                textures().releaseAppearancePins(holder(player.getUUID()));
                shownSkinChanged(player.getUUID(), player);
                lifted++;
            }
        }
        if (lifted == 0) return Outcome.of(SkinAdminStatus.NOTHING);
        LOGGER.info("{} took back the skins they gave {} players", admin, lifted);
        return new Outcome(SkinAdminStatus.DONE, lifted);
    }

    /** Takes back every assigned skin, of the players online and of those who left. */
    public static Outcome liftAll(MinecraftServer server, String admin) {
        List<UUID> lifted = store().liftAll();
        if (lifted.isEmpty()) return Outcome.of(SkinAdminStatus.NOTHING);
        for (UUID playerId : lifted) {
            textures().releaseAppearancePins(holder(playerId));
            shownSkinChanged(playerId, server.getPlayerList().getPlayer(playerId));
        }
        LOGGER.info("{} took back every assigned skin ({} players)", admin, lifted.size());
        return new Outcome(SkinAdminStatus.DONE, lifted.size());
    }

    /** Adds a skin the server holds to the pool, under a name of its own. */
    public static Outcome addToPool(String name, String skinId, String model, String admin) {
        if (!ServerSkinAssignmentStore.isValid(skinId, model) || !PoolSkinEntry.isValidName(name)) {
            return Outcome.of(SkinAdminStatus.INVALID);
        }
        String hash = skinId.substring("local_skin:".length());
        if (!textures().isSkin(hash)) return Outcome.of(SkinAdminStatus.MISSING_TEXTURE);
        ServerSkinAssignmentStore.PoolAddResult result = store().addToPool(name, skinId, model);
        switch (result) {
            case ADDED -> {
                pinPool();
                if (!textures().isPinned(hash)) {
                    // The budget holds no more: the skin would be evicted
                    store().removeFromPool(name);
                    pinPool();
                    return Outcome.of(SkinAdminStatus.NO_ROOM);
                }
                LOGGER.info("{} added the skin {} to the pool", admin, name);
                stateChanged = true;
                return new Outcome(SkinAdminStatus.DONE, 1);
            }
            case NAME_TAKEN, DUPLICATE -> {
                return Outcome.of(SkinAdminStatus.NAME_TAKEN);
            }
            case FULL -> {
                return Outcome.of(SkinAdminStatus.POOL_FULL);
            }
            default -> {
                return Outcome.of(SkinAdminStatus.INVALID);
            }
        }
    }

    /** Removes a skin from the pool; players it was given keep it until it is taken back. */
    public static Outcome removeFromPool(String name, String admin) {
        PoolSkinEntry removed = store().removeFromPool(name);
        if (removed == null) return Outcome.of(SkinAdminStatus.INVALID);
        pinPool();
        stateChanged = true;
        LOGGER.info("{} removed the skin {} from the pool", admin, removed.name());
        return new Outcome(SkinAdminStatus.DONE, 1);
    }

    /** A player's own Quick Skin skin, as a skin id and an explicit model, or null when they have none. */
    @Nullable
    public static String[] ownSkin(UUID playerId) {
        PlayerAppearance own = ServerPlayerAppearanceRepository.getInstance().getAppearance(playerId);
        if (own == null || !own.getSkinId().startsWith("local_skin:")) return null;
        String hash = own.getSkinId().substring("local_skin:".length());
        ServerTextureCache cache = textures();
        // An assigned skin is named by its strong id, which a texture stored under SHA-1 also has
        String strong = NetworkSecurity.isValidStrongContentId(hash)
                ? hash : cache.contentIdFor(hash, ContentId.Algorithm.SHA256);
        if (strong == null || !cache.isSkin(strong)) return null;
        String model = own.getModel();
        if (!"classic".equals(model) && !"slim".equals(model)) {
            // Current clients send the arms they chose; only a small skin is read here
            int size = cache.getTextureSize(strong);
            byte[] texture = size > 0 && size <= MAX_DETECTED_BYTES ? cache.getTexture(strong) : null;
            model = texture != null ? SkinModelDetector.detectSkinModel(texture) : "classic";
        }
        return new String[] {"local_skin:" + strong, model};
    }

    /** The skin a player shows to others: an assigned one, or else their own Quick Skin skin. */
    @Nullable
    public static String[] shownSkin(UUID playerId) {
        ServerSkinAssignmentStore.AssignedSkin assigned = store().assigned(playerId);
        return assigned != null && textures().isSkin(assigned.hash())
                ? new String[] {assigned.skinId(), assigned.model()} : ownSkin(playerId);
    }

    /**
     * Gives one player a skin: 1 when it changed what others see, 0 when they had it already, -1
     * when the store or the texture budget had no room.
     */
    private static int give(ServerPlayer player, String skinId, String model) {
        UUID playerId = player.getUUID();
        ServerSkinAssignmentStore.AssignedSkin current = store().assigned(playerId);
        if (current != null && current.skinId().equals(skinId) && current.model().equals(model)) return 0;
        if (!store().hasRoomFor(playerId) || !textures().tryReplaceSkinPins(
                holder(playerId), List.of(skinId.substring("local_skin:".length())))) return -1;
        store().assign(playerId, skinId, model);
        shownSkinChanged(playerId, player);
        return 1;
    }

    /** Everyone learns the player's skin again; the player's client, when online, its assigned skin. */
    private static void shownSkinChanged(UUID playerId, @Nullable ServerPlayer player) {
        ServerNetworkHandler.appearanceShownChanged(playerId);
        if (player != null) sendAssignedSkin(player);
        stateChanged = true;
    }

    /**
     * Pins the pool's skins that the cache holds, as many as the budget takes, in pool order.
     *
     * @return how many are pinned
     */
    private static int pinPool() {
        List<String> pinned = new ArrayList<>();
        for (PoolSkinEntry skin : store().pool()) {
            String hash = skin.skinId().substring("local_skin:".length());
            if (!textures().isSkin(hash)) continue;
            pinned.add(hash);
            if (!textures().tryReplaceSkinPins(POOL_HOLDER, pinned)) pinned.remove(pinned.size() - 1);
        }
        if (pinned.isEmpty()) textures().releaseAppearancePins(POOL_HOLDER);
        return pinned.size();
    }

    /** The texture-cache holder of one player's assigned skin, an id no player has. */
    private static UUID holder(UUID playerId) {
        return UUID.nameUUIDFromBytes(("quickskin:assigned-skin:" + playerId).getBytes(StandardCharsets.UTF_8));
    }

    // ----- The protocol -----

    /** Whether this exact session negotiated {@code assigned-skins}. */
    static boolean canReceive(ServerPlayer player) {
        if (player == null || !ProtocolNetwork.isReady(player)) return false;
        ProtocolProfile profile = ProtocolNetwork.profile(player);
        return ProtocolNetwork.acceptsV2(profile) && profile.supports(ProtocolCapability.ASSIGNED_SKINS);
    }

    /** Network thread: whether this session may send a {@code skin_admin} request at all. */
    static boolean admits(ServerPlayer sender) {
        return sender != null && admits(ProtocolNetwork.profile(sender));
    }

    /** A request is admitted only from a negotiated v2 session with {@code assigned-skins}. */
    static boolean admits(ProtocolProfile senderProfile) {
        return senderProfile != null && ProtocolNetwork.acceptsV2(senderProfile)
                && senderProfile.supports(ProtocolCapability.ASSIGNED_SKINS);
    }

    /** Server thread: an admin's client asked for something. */
    static void request(ServerPlayer admin, Request request) {
        if (admin == null) return;
        if (!ServerNicknames.isOperator(admin)) {
            PENDING.remove(admin.getUUID());
            sendResult(admin, request.requestId(), Outcome.of(SkinAdminStatus.NOT_ALLOWED));
            return;
        }
        Outcome outcome = run(admin, request);
        if (outcome != null) {
            sendResult(admin, request.requestId(), outcome);
            return;
        }
        // Waits for the upload of the skin it names, behind the admin's other waiting requests
        Deque<PendingRequest> waiting = PENDING.computeIfAbsent(admin.getUUID(), id -> new ArrayDeque<>());
        waiting.removeIf(pending -> pending.connection() != admin.connection);
        while (waiting.size() >= MAX_WAITING) {
            sendResult(admin, waiting.removeFirst().request().requestId(), Outcome.of(SkinAdminStatus.MISSING_TEXTURE));
        }
        waiting.addLast(new PendingRequest(admin.connection, request, ticks + UPLOAD_WAIT_TICKS));
    }

    /** Runs a request, or returns null when it names a skin the server does not hold yet. */
    @Nullable
    private static Outcome run(ServerPlayer admin, Request request) {
        MinecraftServer server = ServerNicknames.server(admin);
        List<ServerPlayer> players = new ArrayList<>();
        for (UUID playerId : request.players()) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null && !players.contains(player)) players.add(player);
        }
        String name = admin.getScoreboardName();
        boolean needsSkin = request.action() == SkinAdminAction.APPLY || request.action() == SkinAdminAction.POOL_ADD;
        if (needsSkin && ServerSkinAssignmentStore.isValid(request.skinId(), request.model())
                && !textures().isSkin(request.skinId().substring("local_skin:".length()))) {
            return null;
        }
        Random random = new Random();
        return switch (request.action()) {
            case APPLY -> assign(players, request.skinId(), request.model(), name);
            case RANDOM -> assignRandom(players, random, name);
            case SHUFFLE -> shuffle(players, random, name);
            case RESET -> lift(players, name);
            case POOL_ADD -> addToPool(request.name(), request.skinId(), request.model(), name);
            case POOL_REMOVE -> removeFromPool(request.name(), name);
        };
    }

    /** Server thread: an upload of this player finished, so requests waiting for it may run. */
    static void uploadFinished(ServerPlayer player) {
        Deque<PendingRequest> waiting = PENDING.get(player.getUUID());
        if (waiting == null || waiting.isEmpty()) return;
        boolean operator = ServerNicknames.isOperator(player);
        for (var iterator = waiting.iterator(); iterator.hasNext(); ) {
            PendingRequest pending = iterator.next();
            if (pending.connection() != player.connection) {
                iterator.remove();
                continue;
            }
            Outcome outcome = operator ? run(player, pending.request()) : Outcome.of(SkinAdminStatus.NOT_ALLOWED);
            if (outcome != null) {
                iterator.remove();
                sendResult(player, pending.request().requestId(), outcome);
            }
        }
        if (waiting.isEmpty()) PENDING.remove(player.getUUID());
    }

    /** A session became ready: it learns its assigned skin, and an admin the pool. */
    static void sessionReady(ServerPlayer player) {
        if (!canReceive(player)) return;
        if (store().assigned(player.getUUID()) != null) sendAssignedSkin(player);
        if (ServerNicknames.isOperator(player)) sendState(player);
    }

    /** Every tick: requests whose upload never came are answered, and admins learn changes. */
    static void tick(MinecraftServer server) {
        ticks++;
        if (!PENDING.isEmpty()) {
            for (var admins = PENDING.entrySet().iterator(); admins.hasNext(); ) {
                Map.Entry<UUID, Deque<PendingRequest>> entry = admins.next();
                ServerPlayer admin = server.getPlayerList().getPlayer(entry.getKey());
                for (var iterator = entry.getValue().iterator(); iterator.hasNext(); ) {
                    PendingRequest pending = iterator.next();
                    if (admin == null || admin.connection != pending.connection()) {
                        iterator.remove();
                    } else if (ticks >= pending.deadline()) {
                        iterator.remove();
                        sendResult(admin, pending.request().requestId(), Outcome.of(SkinAdminStatus.MISSING_TEXTURE));
                    }
                }
                if (entry.getValue().isEmpty()) admins.remove();
            }
        }
        if (stateChanged || ticks % STATE_POLL_TICKS == 0) {
            boolean everyone = stateChanged;
            stateChanged = false;
            Set<UUID> online = new HashSet<>();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                online.add(player.getUUID());
                boolean admin = canReceive(player) && ServerNicknames.isOperator(player);
                if (!admin) {
                    STATE_SENT.remove(player.getUUID());
                } else if (everyone || STATE_SENT.get(player.getUUID()) != player.connection) {
                    // A new admin, or a change every admin must learn
                    sendState(player);
                }
            }
            STATE_SENT.keySet().retainAll(online);
        }
    }

    /** The exact session ended. */
    static void playerLeft(UUID playerId, Object connection) {
        Deque<PendingRequest> waiting = PENDING.get(playerId);
        if (waiting != null) {
            waiting.removeIf(pending -> pending.connection() == connection);
            if (waiting.isEmpty()) PENDING.remove(playerId);
        }
        if (STATE_SENT.get(playerId) == connection) STATE_SENT.remove(playerId);
    }

    /** Forgets every session; the server stopped. */
    public static void clear() {
        PENDING.clear();
        STATE_SENT.clear();
        stateChanged = false;
        ticks = 0;
    }

    private static void sendAssignedSkin(ServerPlayer player) {
        if (!canReceive(player)) return;
        ServerSkinAssignmentStore.AssignedSkin skin = store().assigned(player.getUUID());
        boolean shown = skin != null && textures().isSkin(skin.hash());
        String skinId = shown ? skin.skinId() : "";
        String model = shown ? skin.model() : "";
        try {
            //? if <1.21 {
            NetworkTransport.INSTANCE.sendAssignedSkinToPlayer(player, skinId, model);
            //?} else {
            NetworkTransport.INSTANCE.sendToPlayer(player, new AssignedSkinPayload(skinId, model));
            //?}
        } catch (RuntimeException error) {
            LOGGER.warn("Unable to tell {} about their assigned skin", player.getScoreboardName(), error);
        }
    }

    private static void sendState(ServerPlayer player) {
        STATE_SENT.put(player.getUUID(), player.connection);
        List<PoolSkinEntry> pool = store().pool();
        List<UUID> assigned = new ArrayList<>(store().assignedSkins().keySet());
        if (assigned.size() > TextureTransferLimits.MAX_ASSIGNED_SKIN_ENTRIES) {
            assigned = assigned.subList(assigned.size() - TextureTransferLimits.MAX_ASSIGNED_SKIN_ENTRIES, assigned.size());
        }
        try {
            //? if <1.21 {
            NetworkTransport.INSTANCE.sendSkinAdminStateToPlayer(player, pool, List.copyOf(assigned));
            //?} else {
            NetworkTransport.INSTANCE.sendToPlayer(player, new SkinAdminStatePayload(pool, List.copyOf(assigned)));
            //?}
        } catch (RuntimeException error) {
            LOGGER.warn("Unable to send the skin pool to {}", player.getScoreboardName(), error);
        }
    }

    private static void sendResult(ServerPlayer admin, int requestId, Outcome outcome) {
        if (!canReceive(admin)) return;
        try {
            //? if <1.21 {
            NetworkTransport.INSTANCE.sendSkinAdminResultToPlayer(
                    admin, requestId, outcome.status().code(), outcome.count());
            //?} else {
            NetworkTransport.INSTANCE.sendToPlayer(admin, new SkinAdminResultPayload(
                    requestId, outcome.status().code(), outcome.count()));
            //?}
        } catch (RuntimeException error) {
            LOGGER.warn("Unable to answer the skin request of {}", admin.getScoreboardName(), error);
        }
    }
}
