package com.quickskin.mod.server.data;

import com.quickskin.mod.networking.NetworkSecurity;
import com.quickskin.mod.networking.TextureTransferLimits;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Bounded ordering state for asynchronous texture uploads and their dependent control packets.
 *
 * <p>A session is identified by both the authenticated player UUID and the exact connection
 * object. The coordinator contains no Minecraft types so its ordering, expiration, and isolation
 * rules can be tested without starting a game server.</p>
 */
public final class ServerUploadCoordinator {
    private static final ServerUploadCoordinator INSTANCE =
            new ServerUploadCoordinator(System::currentTimeMillis);

    static final int MAX_SESSIONS = 1024;
    static final int MAX_GLOBAL_UPLOADS = 32;
    static final int MAX_UPLOADS_PER_SESSION = 8;
    static final int MAX_PENDING_METADATA_PER_SESSION = 8;
    static final long PENDING_TTL_MILLIS = 30_000L;
    static final long UPLOAD_TICKET_TTL_MILLIS = 2L * 60 * 1000;
    static final long MAX_RETAINED_UPLOAD_BYTES = 128L * 1024 * 1024;

    private final LongSupplier clock;
    private final Map<SessionKey, SessionState> sessions = new HashMap<>();
    private final Map<Long, PendingUpload> uploads = new HashMap<>();
    private long retainedUploadBytes;
    private long nextTicketId;

    ServerUploadCoordinator(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static ServerUploadCoordinator getInstance() {
        return INSTANCE;
    }

    /** Reserves one bounded in-flight upload slot for the exact player connection. */
    public synchronized UploadTicket beginUpload(
            UUID playerId,
            Object connection,
            String textureType,
            String expectedHash,
            int retainedBytes
    ) {
        if (playerId == null || connection == null
                || !NetworkSecurity.isValidTextureType(textureType)
                || (expectedHash != null
                        && !NetworkSecurity.isValidContentId(expectedHash))
                || retainedBytes <= 0
                || retainedBytes > TextureTransferLimits.MAX_TEXTURE_BYTES) return null;

        long now = clock.getAsLong();
        purgeExpired(now);
        SessionKey key = new SessionKey(playerId, connection);
        SessionState state = sessions.get(key);
        if (state == null) {
            if (sessions.size() >= MAX_SESSIONS) return null;
            state = new SessionState(now + PENDING_TTL_MILLIS);
            sessions.put(key, state);
        }
        if (state.uploadIds.size() >= MAX_UPLOADS_PER_SESSION
                || uploads.size() >= MAX_GLOBAL_UPLOADS
                || retainedUploadBytes > MAX_RETAINED_UPLOAD_BYTES - retainedBytes) return null;

        long ticketId = ++nextTicketId;
        state.uploadIds.add(ticketId);
        uploads.put(ticketId, new PendingUpload(
                key, textureType, expectedHash, retainedBytes,
                now + UPLOAD_TICKET_TTL_MILLIS));
        retainedUploadBytes += retainedBytes;
        state.deadlineMillis = now + PENDING_TTL_MILLIS;
        return new UploadTicket(key, ticketId);
    }

    /** Resolves the server-computed hash of a direct upload before its commit callback runs. */
    public synchronized void identifyUpload(UploadTicket ticket, String hash) {
        if (ticket == null || !NetworkSecurity.isValidContentId(hash)) return;
        purgeExpired(clock.getAsLong());
        PendingUpload upload = uploads.get(ticket.ticketId);
        if (upload != null && upload.sessionKey.equals(ticket.sessionKey)
                && !upload.canceled && upload.hash == null) upload.hash = hash;
    }

    public synchronized boolean isCanceled(UploadTicket ticket) {
        if (ticket == null) return true;
        purgeExpired(clock.getAsLong());
        PendingUpload upload = uploads.get(ticket.ticketId);
        return upload == null || upload.canceled
                || !upload.sessionKey.equals(ticket.sessionKey);
    }

    /**
     * Discards an older deferred appearance when a newer syntactically valid update arrives.
     * This is called before either applying or deferring the newer update, so latest wins.
     */
    public synchronized void supersedeAppearance(UUID playerId, Object connection) {
        if (playerId == null || connection == null) return;
        long now = clock.getAsLong();
        purgeExpired(now);
        SessionKey key = new SessionKey(playerId, connection);
        SessionState state = sessions.get(key);
        if (state != null) {
            state.pendingAppearance = null;
            removeIfIdle(key, state);
        }
    }

    /** Discards older deferred metadata for the same cape when a newer value arrives. */
    public synchronized void supersedeMetadata(
            UUID playerId, Object connection, String hash) {
        if (playerId == null || connection == null || hash == null) return;
        long now = clock.getAsLong();
        purgeExpired(now);
        SessionKey key = new SessionKey(playerId, connection);
        SessionState state = sessions.get(key);
        if (state != null) {
            state.pendingMetadata.remove(hash);
            removeIfIdle(key, state);
        }
    }

    /**
     * Defers the latest appearance only when every missing owned texture has a matching upload
     * in flight in this exact session.
     */
    public synchronized boolean deferAppearance(
            UUID playerId,
            Object connection,
            PendingAppearance appearance,
            String missingSkinHash,
            String missingCapeHash
    ) {
        if (playerId == null || connection == null || appearance == null
                || (missingSkinHash == null && missingCapeHash == null)
                || (missingSkinHash != null
                        && !NetworkSecurity.isValidContentId(missingSkinHash))
                || (missingCapeHash != null
                        && !NetworkSecurity.isValidContentId(missingCapeHash))) return false;
        long now = clock.getAsLong();
        purgeExpired(now);
        SessionKey key = new SessionKey(playerId, connection);
        SessionState state = sessions.get(key);
        if (state == null
                || (missingSkinHash != null
                        && !hasUpload(state, "skin", missingSkinHash))
                || (missingCapeHash != null
                        && !hasUpload(state, "cape", missingCapeHash))) return false;

        state.pendingAppearance = new ExpiringAppearance(
                appearance, now + PENDING_TTL_MILLIS);
        state.deadlineMillis = now + PENDING_TTL_MILLIS;
        return true;
    }

    /** Defers bounded, latest-per-hash metadata behind this session's cape upload. */
    public synchronized boolean deferMetadata(
            UUID playerId, Object connection, PendingMetadata metadata) {
        if (playerId == null || connection == null || metadata == null
                || !NetworkSecurity.isValidContentId(metadata.hash())) return false;
        long now = clock.getAsLong();
        purgeExpired(now);
        SessionKey key = new SessionKey(playerId, connection);
        SessionState state = sessions.get(key);
        if (state == null || !hasUpload(state, "cape", metadata.hash())) return false;

        if (!state.pendingMetadata.containsKey(metadata.hash())
                && state.pendingMetadata.size() >= MAX_PENDING_METADATA_PER_SESSION) {
            Iterator<String> iterator = state.pendingMetadata.keySet().iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
        state.pendingMetadata.put(metadata.hash(), new ExpiringMetadata(
                metadata, now + PENDING_TTL_MILLIS));
        state.deadlineMillis = now + PENDING_TTL_MILLIS;
        return true;
    }

    /**
     * Finishes one upload and atomically drains dependants for an authorization retry. A retry
     * may defer them again when another matching upload from the same session is still running.
     */
    public synchronized RetryBatch finishUpload(UploadTicket ticket) {
        if (ticket == null) return RetryBatch.EMPTY;
        long now = clock.getAsLong();
        purgeExpired(now);
        PendingUpload completed = uploads.remove(ticket.ticketId);
        if (completed == null || !completed.sessionKey.equals(ticket.sessionKey)) {
            return RetryBatch.EMPTY;
        }
        retainedUploadBytes -= completed.retainedBytes;
        SessionState state = sessions.get(ticket.sessionKey);
        if (state == null || !state.uploadIds.remove(ticket.ticketId)
                || completed.canceled) return RetryBatch.EMPTY;

        PendingAppearance appearance = null;
        if (state.pendingAppearance != null) {
            if (state.pendingAppearance.deadlineMillis >= now) {
                appearance = state.pendingAppearance.value;
            }
            state.pendingAppearance = null;
        }

        List<PendingMetadata> metadata = new ArrayList<>();
        for (ExpiringMetadata pending : state.pendingMetadata.values()) {
            if (pending.deadlineMillis >= now) metadata.add(pending.value);
        }
        state.pendingMetadata.clear();
        state.deadlineMillis = now + PENDING_TTL_MILLIS;
        removeIfIdle(ticket.sessionKey, state);
        return appearance == null && metadata.isEmpty()
                ? RetryBatch.EMPTY
                : new RetryBatch(appearance, metadata);
    }

    /** Removes state for one exact connection, including late worker tickets. */
    public synchronized void removeSession(UUID playerId, Object connection) {
        if (playerId == null || connection == null) return;
        SessionState state = sessions.remove(new SessionKey(playerId, connection));
        if (state == null) return;
        for (Long uploadId : state.uploadIds) {
            PendingUpload upload = uploads.get(uploadId);
            if (upload != null) upload.canceled = true;
        }
    }

    /** Cancels authorization state; retained-byte leases live until their holders finish. */
    public synchronized void clear() {
        for (PendingUpload upload : uploads.values()) upload.canceled = true;
        sessions.clear();
    }

    synchronized int sessionCount() {
        purgeExpired(clock.getAsLong());
        return sessions.size();
    }

    private boolean hasUpload(SessionState state, String textureType, String hash) {
        for (Long uploadId : state.uploadIds) {
            PendingUpload upload = uploads.get(uploadId);
            if (upload == null || upload.canceled) continue;
            // Direct uploads are hashless only until their worker finishes hashing. They may
            // tentatively order a dependent packet, which is still re-authorized after commit.
            if (textureType.equals(upload.textureType)
                    && (upload.hash == null || hash.equals(upload.hash))) return true;
        }
        return false;
    }

    private void purgeExpired(long now) {
        for (Map.Entry<Long, PendingUpload> entry : uploads.entrySet()) {
            PendingUpload upload = entry.getValue();
            if (!upload.canceled && upload.deadlineMillis < now) {
                upload.canceled = true;
                SessionState state = sessions.get(upload.sessionKey);
                if (state != null) state.uploadIds.remove(entry.getKey());
            }
        }
        Iterator<Map.Entry<SessionKey, SessionState>> sessionsIterator =
                sessions.entrySet().iterator();
        while (sessionsIterator.hasNext()) {
            SessionState state = sessionsIterator.next().getValue();
            if (state.pendingAppearance != null
                    && state.pendingAppearance.deadlineMillis < now) {
                state.pendingAppearance = null;
            }
            state.pendingMetadata.values().removeIf(
                    pending -> pending.deadlineMillis < now);
            if (state.uploadIds.isEmpty()
                    && state.pendingAppearance == null
                    && state.pendingMetadata.isEmpty()) {
                sessionsIterator.remove();
            } else if (state.uploadIds.isEmpty() && state.deadlineMillis < now) {
                sessionsIterator.remove();
            }
        }
    }

    private void removeIfIdle(SessionKey key, SessionState state) {
        if (state.uploadIds.isEmpty() && state.pendingAppearance == null
                && state.pendingMetadata.isEmpty()) {
            sessions.remove(key);
        }
    }

    public static final class UploadTicket {
        private final SessionKey sessionKey;
        private final long ticketId;

        private UploadTicket(SessionKey sessionKey, long ticketId) {
            this.sessionKey = sessionKey;
            this.ticketId = ticketId;
        }
    }

    public record PendingAppearance(String skinId, String capeId, String model) {
    }

    public record PendingMetadata(String hash, String metadataJson) {
    }

    public record RetryBatch(
            PendingAppearance appearance, List<PendingMetadata> metadata) {
        private static final RetryBatch EMPTY =
                new RetryBatch(null, Collections.emptyList());

        public RetryBatch {
            metadata = List.copyOf(metadata);
        }
    }

    private static final class SessionState {
        private final Set<Long> uploadIds = new LinkedHashSet<>();
        private final LinkedHashMap<String, ExpiringMetadata> pendingMetadata =
                new LinkedHashMap<>();
        private ExpiringAppearance pendingAppearance;
        private long deadlineMillis;

        private SessionState(long deadlineMillis) {
            this.deadlineMillis = deadlineMillis;
        }
    }

    private static final class PendingUpload {
        private final SessionKey sessionKey;
        private final String textureType;
        private final int retainedBytes;
        private final long deadlineMillis;
        private String hash;
        private boolean canceled;

        private PendingUpload(
                SessionKey sessionKey,
                String textureType,
                String hash,
                int retainedBytes,
                long deadlineMillis
        ) {
            this.sessionKey = sessionKey;
            this.textureType = textureType;
            this.hash = hash;
            this.retainedBytes = retainedBytes;
            this.deadlineMillis = deadlineMillis;
        }
    }

    private record ExpiringAppearance(PendingAppearance value, long deadlineMillis) {
    }

    private record ExpiringMetadata(PendingMetadata value, long deadlineMillis) {
    }

    /** Identity equality for the connection prevents UUID reuse from crossing sessions. */
    private static final class SessionKey {
        private final UUID playerId;
        private final Object connection;

        private SessionKey(UUID playerId, Object connection) {
            this.playerId = playerId;
            this.connection = connection;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof SessionKey key)) return false;
            return playerId.equals(key.playerId) && connection == key.connection;
        }

        @Override
        public int hashCode() {
            return 31 * playerId.hashCode() + System.identityHashCode(connection);
        }
    }
}
