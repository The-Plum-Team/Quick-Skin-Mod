package com.quickskin.mod.server.data;

import com.quickskin.mod.networking.TextureTransferLimits;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Bounded reservations for texture responses prepared away from the server thread. */
public final class ServerTextureResponseCoordinator {
    private static final ServerTextureResponseCoordinator INSTANCE =
            new ServerTextureResponseCoordinator();
    private static final int MAX_PENDING_RESPONSES = 32;
    private static final int MAX_PENDING_PER_SESSION = 2;
    private static final long MAX_RETAINED_RESPONSE_BYTES =
            TextureTransferLimits.MAX_SERVER_ASSEMBLY_BYTES;

    private final Map<Long, Reservation> reservations = new HashMap<>();
    private final Map<SessionKey, Integer> sessionCounts = new HashMap<>();
    private long retainedBytes;
    private long nextId;

    ServerTextureResponseCoordinator() {
    }

    public static ServerTextureResponseCoordinator getInstance() {
        return INSTANCE;
    }

    public synchronized ResponseTicket reserve(
            UUID playerId, Object connection, int byteCount) {
        if (playerId == null || connection == null || byteCount <= 0
                || byteCount > TextureTransferLimits.MAX_TEXTURE_BYTES
                || reservations.size() >= MAX_PENDING_RESPONSES
                || retainedBytes > MAX_RETAINED_RESPONSE_BYTES - byteCount) return null;
        SessionKey key = new SessionKey(playerId, connection);
        int sessionCount = sessionCounts.getOrDefault(key, 0);
        if (sessionCount >= MAX_PENDING_PER_SESSION) return null;

        long id = ++nextId;
        reservations.put(id, new Reservation(key, byteCount));
        sessionCounts.put(key, sessionCount + 1);
        retainedBytes += byteCount;
        return new ResponseTicket(id);
    }

    public synchronized void release(ResponseTicket ticket) {
        if (ticket == null) return;
        Reservation reservation = reservations.remove(ticket.id);
        if (reservation == null) return;
        retainedBytes -= reservation.byteCount;
        decrementSession(reservation.sessionKey);
    }

    public synchronized boolean isCanceled(ResponseTicket ticket) {
        if (ticket == null) return true;
        Reservation reservation = reservations.get(ticket.id);
        return reservation == null || reservation.canceled;
    }

    /** Marks exact-session work stale without freeing bytes still held by a worker/callback. */
    public synchronized void cancelSession(UUID playerId, Object connection) {
        if (playerId == null || connection == null) return;
        SessionKey key = new SessionKey(playerId, connection);
        for (Reservation reservation : reservations.values()) {
            if (reservation.sessionKey.equals(key)) reservation.canceled = true;
        }
    }

    /** Server-stop cancellation; holders release their leases after relinquishing byte arrays. */
    public synchronized void cancelAll() {
        for (Reservation reservation : reservations.values()) reservation.canceled = true;
    }

    private void decrementSession(SessionKey key) {
        int count = sessionCounts.getOrDefault(key, 0);
        if (count <= 1) sessionCounts.remove(key);
        else sessionCounts.put(key, count - 1);
    }

    public static final class ResponseTicket {
        private final long id;

        private ResponseTicket(long id) {
            this.id = id;
        }
    }

    private static final class Reservation {
        private final SessionKey sessionKey;
        private final int byteCount;
        private boolean canceled;

        private Reservation(SessionKey sessionKey, int byteCount) {
            this.sessionKey = sessionKey;
            this.byteCount = byteCount;
        }
    }

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
            return Objects.hash(playerId, System.identityHashCode(connection));
        }
    }
}
