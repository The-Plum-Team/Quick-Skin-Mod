package com.quickskin.mod.networking;

import com.quickskin.mod.client.api.ClientNetworkActions.SkinAdminAnswer;
import com.quickskin.mod.client.api.ClientNetworkActions.SkinAdminView;
import com.quickskin.mod.client.api.RemoteAppearanceTarget;
import com.quickskin.mod.client.concurrent.ClientIoExecutor;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.common.util.HashUtil;
import com.quickskin.mod.networking.protocol.ProtocolCapability;
import com.quickskin.mod.networking.protocol.ProtocolProfile;
import com.quickskin.mod.networking.protocol.ProtocolSessions;
import com.quickskin.mod.platform.QuickSkinInfo;
//? if >=1.21 {
import com.quickskin.mod.networking.payloads.SkinAdminPayload;
import com.quickskin.mod.networking.payloads.TextureChunkV2Payload;
//?}
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client side of assigned skins (see {@code ServerSkinAssignments}), on the client thread: the
 * skin an admin gave the local player, which it shows on itself, and, for an admin, the server's
 * skin pool and the requests that hand out skins. A skin from the admin's own catalog is uploaded
 * first, in paced chunks, once per session, and its request sent right after; the server runs it
 * once the upload is stored. Every request is answered, by the server or, after
 * {@link #ANSWER_TIMEOUT_MILLIS}, here.
 */
@Environment(EnvType.CLIENT)
public final class SkinAdminClient {
    private static final SkinAdminClient INSTANCE = new SkinAdminClient();
    private static final int CHUNKS_PER_TICK = 4;
    /** Packets that may wait their turn: texture chunks and the requests that name them. */
    private static final int MAX_QUEUED_PACKETS = 2048;
    /** Requests that may wait for an answer at once; the oldest is given up past it. */
    private static final int MAX_PENDING = 32;
    /** Answers kept for a screen that has not shown them yet. */
    private static final int MAX_ANSWERS = 16;
    /** Skins uploaded this session, which the server holds and need not be sent again. */
    private static final int MAX_UPLOADED = 64;
    /** Longer than the server waits for an upload (30 s), so its answer comes first when it can. */
    static final long ANSWER_TIMEOUT_MILLIS = 45_000L;

    private Object connection;
    private SkinAdminView view;
    private int answerSequence;
    private final Deque<SkinAdminAnswer> answers = new ArrayDeque<>();
    private int nextRequestId = 1;
    /** Sent requests waiting for their answer, by request number, oldest first. */
    private final Map<Integer, Pending> pending = new LinkedHashMap<>();
    private final LinkedHashSet<String> uploaded = new LinkedHashSet<>();
    private String assignedSkinId = "";
    private PendingAssigned pendingAssigned;
    /** Packets waiting for their turn: texture chunks, then the request that names them. */
    private final Deque<Runnable> outgoing = new ArrayDeque<>();

    /** What a sent request did, the skin it uploaded (or null) and when it was sent. */
    private record Pending(SkinAdminAnswer.Kind kind, @Nullable String uploadedContentId, long sentAtMillis) {
    }

    private record PendingAssigned(Object connection, String skinId, String model, RemoteAppearanceTarget target) {
    }

    private SkinAdminClient() {
    }

    public static SkinAdminClient getInstance() {
        return INSTANCE;
    }

    // ----- The local player's assigned skin -----

    /** The server says which skin an admin gave the local player ({@code ""}: none). */
    synchronized void onAssignedSkin(Object sourceConnection, String skinId, String model,
                                     RemoteAppearanceTarget target) {
        if (currentConnection() != sourceConnection) return;
        pendingAssigned = new PendingAssigned(sourceConnection, skinId, model, target);
        applyPendingAssigned();
    }

    /** Applies the latest assigned skin once the local player exists. */
    private void applyPendingAssigned() {
        PendingAssigned assigned = pendingAssigned;
        Minecraft minecraft = Minecraft.getInstance();
        if (assigned == null || minecraft.player == null) return;
        pendingAssigned = null;
        if (currentConnection() != assigned.connection()) return;
        String previous = assignedSkinId;
        assignedSkinId = assigned.skinId();
        assigned.target().applyAssignedSkin(minecraft.player.getUUID(), assigned.skinId(), assigned.model());
        if (!assigned.skinId().equals(previous)) {
            tell(Component.translatable(assigned.skinId().isEmpty()
                    ? "quickskin.skin.assignment_lifted" : "quickskin.skin.assigned"));
        }
    }

    private static void tell(Component message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        //? if <26.1 {
        minecraft.player.displayClientMessage(message, false);
        //?} else {
        minecraft.player.sendSystemMessage(message);
        //?}
    }

    // ----- What an admin's client knows and asks -----

    /** The server sent its pool and who has an assigned skin. */
    synchronized void onState(Object sourceConnection, List<PoolSkinEntry> pool, List<UUID> assigned) {
        List<SkinAdminView.PoolSkin> skins = new ArrayList<>(pool.size());
        for (PoolSkinEntry entry : pool) {
            skins.add(new SkinAdminView.PoolSkin(entry.name(),
                    entry.skinId().substring("local_skin:".length()), entry.model()));
        }
        connection = sourceConnection;
        view = new SkinAdminView(List.copyOf(skins), java.util.Set.copyOf(new LinkedHashSet<>(assigned)));
    }

    /** The server answered a request of this session. */
    synchronized void onAnswer(Object sourceConnection, int requestId, SkinAdminStatus status, int count) {
        if (sourceConnection != currentConnection()) return;
        Pending request = pending.remove(requestId);
        if (request == null) return;
        SkinAdminAnswer.Outcome outcome = switch (status) {
            case DONE -> SkinAdminAnswer.Outcome.DONE;
            case NOT_ALLOWED -> SkinAdminAnswer.Outcome.NOT_ALLOWED;
            case MISSING_TEXTURE -> SkinAdminAnswer.Outcome.MISSING_TEXTURE;
            case POOL_EMPTY -> SkinAdminAnswer.Outcome.POOL_EMPTY;
            case POOL_FULL -> SkinAdminAnswer.Outcome.POOL_FULL;
            case NAME_TAKEN -> SkinAdminAnswer.Outcome.NAME_TAKEN;
            case NO_ROOM -> SkinAdminAnswer.Outcome.NO_ROOM;
            case INVALID -> SkinAdminAnswer.Outcome.INVALID;
            case NOTHING -> SkinAdminAnswer.Outcome.NOTHING;
        };
        if (outcome == SkinAdminAnswer.Outcome.MISSING_TEXTURE && request.uploadedContentId() != null) {
            // The server lost it, or never got it: the next request uploads it again
            uploaded.remove(request.uploadedContentId());
        }
        answer(request.kind(), outcome, count);
    }

    /** The pool and who has a skin, while the player is an admin on a server that takes requests. */
    @Nullable
    public synchronized SkinAdminView view() {
        return connection == currentConnection() && canAdminister() ? view : null;
    }

    public synchronized int answerSequence() {
        return answerSequence;
    }

    /** The kept answers newer than this sequence number, oldest first. */
    public synchronized List<SkinAdminAnswer> answersAfter(int sequence) {
        List<SkinAdminAnswer> newer = new ArrayList<>();
        for (SkinAdminAnswer answer : answers) {
            if (answer.sequence() > sequence) newer.add(answer);
        }
        return newer;
    }

    /** Gives players a skin of the admin's catalog: uploads it, then asks. */
    public synchronized boolean giveLocalSkin(Collection<UUID> players, String localHash, String model) {
        return uploadThen(localHash, SkinAdminAnswer.Kind.GIVE, (contentId) -> send(SkinAdminAction.APPLY,
                SkinAdminAnswer.Kind.GIVE, contentId, players, "local_skin:" + contentId, model, ""));
    }

    /**
     * Adds a skin of the admin's catalog to the server's pool, under a pool name made from its
     * name (with a number added when the pool has that name): uploads it, then asks.
     */
    public synchronized boolean addLocalSkinToPool(String skinName, String localHash, String model) {
        String base = PoolSkinEntry.nameFrom(skinName, "skin");
        String name = base;
        SkinAdminView current = view;
        for (int number = 2; current != null && hasPoolSkin(current, name) && number < 100; number++) {
            String suffix = "_" + number;
            name = base.substring(0, Math.min(base.length(),
                    TextureTransferLimits.MAX_POOL_SKIN_NAME_CHARS - suffix.length())) + suffix;
        }
        String poolName = name;
        return uploadThen(localHash, SkinAdminAnswer.Kind.ADD_TO_POOL, (contentId) -> send(SkinAdminAction.POOL_ADD,
                SkinAdminAnswer.Kind.ADD_TO_POOL, contentId, List.of(), "local_skin:" + contentId, model, poolName));
    }

    private static boolean hasPoolSkin(SkinAdminView view, String name) {
        for (SkinAdminView.PoolSkin skin : view.pool()) {
            if (skin.name().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /** Asks for an action that needs no upload. */
    public synchronized boolean action(SkinAdminAction action, Collection<UUID> players, String poolSkinName) {
        if (!canAdminister() || outgoing.size() >= MAX_QUEUED_PACKETS) return false;
        if (action == SkinAdminAction.APPLY) {
            // A pool skin: the server holds it already
            SkinAdminView current = view;
            SkinAdminView.PoolSkin skin = null;
            if (current != null) {
                for (SkinAdminView.PoolSkin candidate : current.pool()) {
                    if (candidate.name().equalsIgnoreCase(poolSkinName)) skin = candidate;
                }
            }
            if (skin == null) return false;
            SkinAdminView.PoolSkin chosen = skin;
            outgoing.addLast(() -> send(SkinAdminAction.APPLY, SkinAdminAnswer.Kind.GIVE, null, players,
                    "local_skin:" + chosen.contentId(), chosen.model(), ""));
            return true;
        }
        SkinAdminAnswer.Kind kind = switch (action) {
            case RANDOM -> SkinAdminAnswer.Kind.RANDOM;
            case SHUFFLE -> SkinAdminAnswer.Kind.SHUFFLE;
            case RESET -> SkinAdminAnswer.Kind.RESTORE;
            case POOL_REMOVE -> SkinAdminAnswer.Kind.REMOVE_FROM_POOL;
            default -> SkinAdminAnswer.Kind.GIVE;
        };
        outgoing.addLast(() -> send(action, kind, null, players, "", "", poolSkinName));
        return true;
    }

    /**
     * Client tick: applies an assigned skin that arrived early, gives up on requests the server
     * never answered and sends a few waiting packets. A packet waits, rather than being dropped,
     * while the player is not an admin for a moment (a respawn re-sends the permission level).
     */
    public synchronized void tick() {
        if (pendingAssigned != null) applyPendingAssigned();
        if (!pending.isEmpty()) {
            long now = System.currentTimeMillis();
            for (Iterator<Pending> iterator = pending.values().iterator(); iterator.hasNext(); ) {
                Pending request = iterator.next();
                if (now - request.sentAtMillis() < ANSWER_TIMEOUT_MILLIS) break;
                iterator.remove();
                answer(request.kind(), SkinAdminAnswer.Outcome.NO_ANSWER, 0);
            }
        }
        if (outgoing.isEmpty() || !canAdminister()) return;
        for (int i = 0; i < CHUNKS_PER_TICK && !outgoing.isEmpty(); i++) {
            try {
                outgoing.removeFirst().run();
            } catch (RuntimeException error) {
                QuickSkinInfo.LOGGER.warn("Unable to send a skin admin packet", error);
                outgoing.clear();
            }
        }
    }

    public synchronized void clearSession() {
        assignedSkinId = "";
        pendingAssigned = null;
        connection = null;
        view = null;
        answers.clear();
        pending.clear();
        uploaded.clear();
        outgoing.clear();
    }

    /** Whether this session negotiated {@code assigned-skins} and the player is an admin here. */
    public boolean canAdminister() {
        Minecraft minecraft = Minecraft.getInstance();
        Object current = minecraft.getConnection();
        if (current == null || minecraft.player == null) return false;
        ProtocolProfile profile = ProtocolSessions.getInstance().clientProfile(current);
        if (!profile.negotiated() || !profile.supports(ProtocolCapability.ASSIGNED_SKINS)) return false;
        //? if <1.21.11 {
        return minecraft.player.hasPermissions(2);
        //?} else {
        return minecraft.player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
        //?}
    }

    private void answer(SkinAdminAnswer.Kind kind, SkinAdminAnswer.Outcome outcome, int count) {
        answers.addLast(new SkinAdminAnswer(++answerSequence, kind, outcome, count));
        while (answers.size() > MAX_ANSWERS) answers.removeFirst();
    }

    /**
     * Reads the catalog skin's canonical bytes off the client thread, then queues its chunks,
     * unless this session uploaded it already, and the request that names it.
     */
    private boolean uploadThen(String localHash, SkinAdminAnswer.Kind kind, java.util.function.Consumer<String> request) {
        if (!canAdminister() || outgoing.size() >= MAX_QUEUED_PACKETS) return false;
        Object requestConnection = currentConnection();
        ProtocolProfile profile = ProtocolSessions.getInstance().clientProfile(requestConnection);
        ClientIoExecutor.supplyAsync(() -> LocalAssetManager.getInstance().loadCanonicalTexture(localHash, "skin"))
                .whenComplete((bytes, error) -> Minecraft.getInstance().execute(() -> {
                    synchronized (SkinAdminClient.this) {
                        if (currentConnection() != requestConnection) return;
                        if (error != null || bytes == null || bytes.length > profile.maximumUploadBytes()) {
                            answer(kind, SkinAdminAnswer.Outcome.MISSING_TEXTURE, 0);
                            return;
                        }
                        String contentId = HashUtil.computeContentId(bytes);
                        int chunkSize = Math.min(TextureTransferLimits.CHUNK_BYTES, profile.maximumChunkBytes());
                        int total = chunkSize < 1 ? 0 : (bytes.length + chunkSize - 1) / chunkSize;
                        if (!NetworkSecurity.isValidStrongContentId(contentId)
                                || total < 1 || total > TextureTransferLimits.MAX_CHUNKS
                                || outgoing.size() + total + 1 > MAX_QUEUED_PACKETS) {
                            answer(kind, SkinAdminAnswer.Outcome.MISSING_TEXTURE, 0);
                            return;
                        }
                        if (!uploaded.contains(contentId)) {
                            for (int index = 0; index < total; index++) {
                                int offset = index * chunkSize;
                                byte[] chunk = java.util.Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + chunkSize));
                                int chunkIndex = index;
                                outgoing.addLast(() -> sendChunk(contentId, chunkIndex, total, chunk));
                            }
                            uploaded.add(contentId);
                            while (uploaded.size() > MAX_UPLOADED) uploaded.remove(uploaded.iterator().next());
                        }
                        outgoing.addLast(() -> request.accept(contentId));
                    }
                }));
        return true;
    }

    private void sendChunk(String contentId, int index, int total, byte[] chunk) {
        //? if <1.21 {
        NetworkTransport.INSTANCE.sendTextureChunkV2ToServer(contentId, "skin", index, total, chunk);
        //?} else {
        NetworkTransport.INSTANCE.sendToServer(new TextureChunkV2Payload(contentId, "skin", index, total, chunk));
        //?}
    }

    private void send(SkinAdminAction action, SkinAdminAnswer.Kind kind, @Nullable String uploadedContentId,
                      Collection<UUID> players, String skinId, String model, String name) {
        List<UUID> targets = new ArrayList<>(new LinkedHashSet<>(players));
        if (targets.size() > TextureTransferLimits.MAX_SKIN_ADMIN_PLAYERS) {
            targets = targets.subList(0, TextureTransferLimits.MAX_SKIN_ADMIN_PLAYERS);
        }
        int requestId = nextRequestId++;
        if (nextRequestId <= 0) nextRequestId = 1;
        //? if <1.21 {
        NetworkTransport.INSTANCE.sendSkinAdminToServer(requestId, action.code(), List.copyOf(targets), skinId, model, name);
        //?} else {
        if (!NetworkTransport.INSTANCE.canServerReceive(SkinAdminPayload.TYPE)) {
            answer(kind, SkinAdminAnswer.Outcome.NO_ANSWER, 0);
            return;
        }
        NetworkTransport.INSTANCE.sendToServer(new SkinAdminPayload(
                requestId, action.code(), List.copyOf(targets), skinId, model, name));
        //?}
        pending.put(requestId, new Pending(kind, uploadedContentId, System.currentTimeMillis()));
        while (pending.size() > MAX_PENDING) {
            Iterator<Pending> oldest = pending.values().iterator();
            Pending given = oldest.next();
            oldest.remove();
            answer(given.kind(), SkinAdminAnswer.Outcome.NO_ANSWER, 0);
        }
    }

    @Nullable
    private static Object currentConnection() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null ? null : minecraft.getConnection();
    }
}
