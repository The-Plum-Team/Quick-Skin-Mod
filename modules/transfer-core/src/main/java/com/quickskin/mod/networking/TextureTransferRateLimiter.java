package com.quickskin.mod.networking;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Bounded per-connection rate state for untrusted texture traffic. */
public final class TextureTransferRateLimiter {
    private static final TextureTransferRateLimiter INSTANCE = new TextureTransferRateLimiter();
    private static final int MAX_TRACKED_SESSIONS = 4096;
    private static final long STATE_TTL_MILLIS = 2L * 60 * 1000;
    private static final long UPLOAD_WINDOW_MILLIS = 60_000L;
    private static final long MAX_UPLOAD_BYTES_PER_WINDOW =
            2L * TextureTransferLimits.MAX_TEXTURE_BYTES;
    private static final int MAX_UPLOAD_PACKETS_PER_WINDOW =
            2 * TextureTransferLimits.MAX_CHUNKS + 128;
    private static final int MAX_STORAGE_MUTATIONS_PER_WINDOW = 32;
    private static final long MAX_DECODED_PIXELS_PER_WINDOW =
            2L * TextureTransferLimits.MAX_IMAGE_PIXELS;
    private static final int MAX_REQUESTS_PER_WINDOW = 64;
    private static final int MAX_APPEARANCE_SNAPSHOT_REQUESTS_PER_WINDOW = 4;
    private static final long REQUEST_WINDOW_MILLIS = 10_000L;
    private static final long MAX_DOWNLOAD_BYTES_PER_WINDOW =
            4L * TextureTransferLimits.MAX_TEXTURE_BYTES;
    /** Matches the client's bounded retry count while preventing unbounded main-thread queues. */
    private static final int MAX_PROTOCOL_HELLO_TOKENS = 5;
    private static final long PROTOCOL_HELLO_REFILL_MILLIS = 2_000L;

    private final LinkedHashMap<SessionKey, SessionState> sessions =
            new LinkedHashMap<>(16, 0.75f, true);

    private TextureTransferRateLimiter() {
    }

    public static TextureTransferRateLimiter getInstance() {
        return INSTANCE;
    }

    public synchronized boolean allowUploadBytes(
            UUID playerId, Object session, int byteCount) {
        if (byteCount <= 0 || byteCount > TextureTransferLimits.MAX_WIRE_CHUNK_BYTES) return false;
        long now = System.currentTimeMillis();
        SessionState state = state(playerId, session, now);
        if (state == null) return false;
        resetUploadWindowIfNeeded(state, now);
        if (state.uploadBytes + byteCount > MAX_UPLOAD_BYTES_PER_WINDOW
                || state.uploadPackets >= MAX_UPLOAD_PACKETS_PER_WINDOW) return false;
        state.uploadBytes += byteCount;
        state.uploadPackets++;
        state.lastActivity = now;
        return true;
    }

    /** Limits decode, disk-write, and broadcast work after an upload completes. */
    public synchronized boolean allowStorageMutation(UUID playerId, Object session) {
        long now = System.currentTimeMillis();
        SessionState state = state(playerId, session, now);
        if (state == null) return false;
        resetUploadWindowIfNeeded(state, now);
        if (state.storageMutations >= MAX_STORAGE_MUTATIONS_PER_WINDOW) return false;
        state.storageMutations++;
        state.lastActivity = now;
        return true;
    }

    /** Caps the total decoded allocation admitted for one authenticated connection. */
    public synchronized boolean allowDecodedPixels(
            UUID playerId, Object session, long pixelCount) {
        if (pixelCount <= 0 || pixelCount > TextureTransferLimits.MAX_IMAGE_PIXELS) return false;
        long now = System.currentTimeMillis();
        SessionState state = state(playerId, session, now);
        if (state == null) return false;
        resetUploadWindowIfNeeded(state, now);
        if (state.decodedPixels > MAX_DECODED_PIXELS_PER_WINDOW - pixelCount) return false;
        state.decodedPixels += pixelCount;
        state.lastActivity = now;
        return true;
    }

    public synchronized boolean allowTextureRequest(UUID playerId, Object session) {
        long now = System.currentTimeMillis();
        SessionState state = state(playerId, session, now);
        if (state == null) return false;
        if (now - state.requestWindowStarted >= REQUEST_WINDOW_MILLIS) {
            state.requestWindowStarted = now;
            state.requests = 0;
            state.appearanceSnapshotRequests = 0;
            state.downloadBytes = 0;
        }
        if (state.requests >= MAX_REQUESTS_PER_WINDOW) return false;
        state.requests++;
        state.lastActivity = now;
        return true;
    }

    /**
     * Admits protocol hellos on the network thread before they enqueue main-thread work.
     *
     * <p>The small per-connection token bucket permits the client's finite retry burst, then
     * replenishes slowly. Exact-session cleanup prevents an old disconnect from resetting a
     * replacement connection's budget.</p>
     */
    public synchronized boolean allowProtocolHello(UUID playerId, Object session) {
        long now = System.currentTimeMillis();
        SessionState state = state(playerId, session, now);
        if (state == null) return false;
        refillProtocolHelloTokens(state, now);
        if (state.protocolHelloTokens < 1) return false;
        state.protocolHelloTokens--;
        state.lastActivity = now;
        return true;
    }

    /** Keeps full-roster retries cheap and independent from texture-fetch admission. */
    public synchronized boolean allowAppearanceSnapshotRequest(
            UUID playerId, Object session) {
        long now = System.currentTimeMillis();
        SessionState state = state(playerId, session, now);
        if (state == null) return false;
        if (now - state.requestWindowStarted >= REQUEST_WINDOW_MILLIS) {
            state.requestWindowStarted = now;
            state.requests = 0;
            state.appearanceSnapshotRequests = 0;
            state.downloadBytes = 0;
        }
        if (state.appearanceSnapshotRequests
                >= MAX_APPEARANCE_SNAPSHOT_REQUESTS_PER_WINDOW) return false;
        state.appearanceSnapshotRequests++;
        state.lastActivity = now;
        return true;
    }

    /** Caps S2C amplification from requests, broadcasts, and initial appearance sync. */
    public synchronized boolean allowDownloadBytes(
            UUID playerId, Object session, int byteCount) {
        return reserveDownloadBytes(playerId, session, byteCount) != null;
    }

    /** Reserves response bytes and returns an exact-window token that can be safely refunded. */
    public synchronized DownloadReservation reserveDownloadBytes(
            UUID playerId, Object session, int byteCount) {
        if (byteCount <= 0 || byteCount > TextureTransferLimits.MAX_TEXTURE_BYTES) return null;
        long now = System.currentTimeMillis();
        SessionState state = state(playerId, session, now);
        if (state == null) return null;
        if (now - state.requestWindowStarted >= REQUEST_WINDOW_MILLIS) {
            state.requestWindowStarted = now;
            state.requests = 0;
            state.appearanceSnapshotRequests = 0;
            state.downloadBytes = 0;
        }
        if (state.downloadBytes + byteCount > MAX_DOWNLOAD_BYTES_PER_WINDOW) return null;
        state.downloadBytes += byteCount;
        state.lastActivity = now;
        return new DownloadReservation(
                new SessionKey(playerId, session), state.requestWindowStarted, byteCount);
    }

    /** Refunds only the same still-active accounting window, and only once. */
    public synchronized void refundDownloadBytes(DownloadReservation reservation) {
        if (reservation == null || reservation.settled) return;
        reservation.settled = true;
        SessionState state = sessions.get(reservation.sessionKey);
        if (state == null || state.requestWindowStarted != reservation.windowStarted) return;
        state.downloadBytes = Math.max(0, state.downloadBytes - reservation.byteCount);
    }

    /** Marks a reservation delivered so a racing cleanup can never refund it. */
    public synchronized void commitDownloadBytes(DownloadReservation reservation) {
        if (reservation != null) reservation.settled = true;
    }

    public synchronized void removePlayer(UUID playerId) {
        if (playerId == null) return;
        Iterator<SessionKey> iterator = sessions.keySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().playerId.equals(playerId)) iterator.remove();
        }
    }

    /** Removes only the disconnecting connection, preserving a rapid reconnect's fresh state. */
    public synchronized void removeSession(UUID playerId, Object session) {
        if (playerId == null || session == null) return;
        sessions.remove(new SessionKey(playerId, session));
    }

    public synchronized void clear() {
        sessions.clear();
    }

    private SessionState state(UUID playerId, Object session, long now) {
        if (playerId == null || session == null) return null;
        purgeExpired(now);
        SessionKey key = new SessionKey(playerId, session);
        SessionState state = sessions.get(key);
        if (state != null) return state;
        while (sessions.size() >= MAX_TRACKED_SESSIONS) {
            Iterator<SessionKey> iterator = sessions.keySet().iterator();
            if (!iterator.hasNext()) return null;
            iterator.next();
            iterator.remove();
        }
        state = new SessionState(now);
        sessions.put(key, state);
        return state;
    }

    private void purgeExpired(long now) {
        Iterator<Map.Entry<SessionKey, SessionState>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().getValue().lastActivity >= STATE_TTL_MILLIS) iterator.remove();
        }
    }

    private void resetUploadWindowIfNeeded(SessionState state, long now) {
        if (now - state.uploadWindowStarted < UPLOAD_WINDOW_MILLIS) return;
        state.uploadWindowStarted = now;
        state.uploadBytes = 0;
        state.uploadPackets = 0;
        state.storageMutations = 0;
        state.decodedPixels = 0;
    }

    private void refillProtocolHelloTokens(SessionState state, long now) {
        long elapsed = now - state.protocolHelloRefillAt;
        if (elapsed < PROTOCOL_HELLO_REFILL_MILLIS) return;
        long tokens = elapsed / PROTOCOL_HELLO_REFILL_MILLIS;
        state.protocolHelloTokens = (int) Math.min(
                MAX_PROTOCOL_HELLO_TOKENS,
                (long) state.protocolHelloTokens + tokens);
        state.protocolHelloRefillAt += tokens * PROTOCOL_HELLO_REFILL_MILLIS;
    }

    private static final class SessionKey {
        private final UUID playerId;
        private final Object session;

        private SessionKey(UUID playerId, Object session) {
            this.playerId = playerId;
            this.session = session;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof SessionKey)) return false;
            SessionKey key = (SessionKey) other;
            return playerId.equals(key.playerId) && session == key.session;
        }

        @Override
        public int hashCode() {
            return Objects.hash(playerId, System.identityHashCode(session));
        }
    }

    public static final class DownloadReservation {
        private final SessionKey sessionKey;
        private final long windowStarted;
        private final int byteCount;
        private boolean settled;

        private DownloadReservation(
                SessionKey sessionKey, long windowStarted, int byteCount) {
            this.sessionKey = sessionKey;
            this.windowStarted = windowStarted;
            this.byteCount = byteCount;
        }
    }

    private static final class SessionState {
        private long uploadWindowStarted;
        private long uploadBytes;
        private int uploadPackets;
        private int storageMutations;
        private long decodedPixels;
        private long requestWindowStarted;
        private int requests;
        private int appearanceSnapshotRequests;
        private long downloadBytes;
        private int protocolHelloTokens;
        private long protocolHelloRefillAt;
        private long lastActivity;

        private SessionState(long now) {
            uploadWindowStarted = now;
            requestWindowStarted = now;
            protocolHelloTokens = MAX_PROTOCOL_HELLO_TOKENS;
            protocolHelloRefillAt = now;
            lastActivity = now;
        }
    }
}
