package com.quickskin.mod.networking;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import java.util.function.Supplier;
import java.util.function.LongSupplier;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Deduplicates renderer-driven texture misses while allowing bounded retries. */
@Environment(EnvType.CLIENT)
public final class TextureRequestCoordinator {
    private static final TextureRequestCoordinator INSTANCE = new TextureRequestCoordinator(
            () -> { throw new IllegalStateException("Texture request connection is not configured"); },
            System::currentTimeMillis);

    private final LinkedHashMap<RequestKey, Long> pending = new LinkedHashMap<>();
    private Object connectionIdentity;
    private Supplier<?> connection;
    private final LongSupplier clock;
    private static boolean configured;

    public TextureRequestCoordinator(Supplier<?> connection, LongSupplier clock) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Installs the process-owned connection source before any client session starts. */
    public static synchronized void configureConnection(Supplier<?> connection) {
        if (configured) throw new IllegalStateException("Texture request connection is already configured");
        synchronized (INSTANCE) {
            INSTANCE.connection = Objects.requireNonNull(connection, "connection");
            INSTANCE.clear();
        }
        configured = true;
    }

    public static TextureRequestCoordinator getInstance() {
        return INSTANCE;
    }

    /**
     * Runs the request only when it is not already pending. A request becomes
     * eligible again after the retry timeout, and changing server sessions resets all state.
     */
    public synchronized boolean requestIfNeeded(String textureType, String hash, Runnable request) {
        if (!NetworkSecurity.isValidTextureType(textureType)
                || !NetworkSecurity.isValidContentId(hash) || request == null) {
            return false;
        }
        refreshSession();
        long now = clock.getAsLong();
        purgeExpired(now);
        RequestKey key = new RequestKey(textureType, hash);
        Long requestedAt = pending.get(key);
        if (requestedAt != null && now - requestedAt < TextureTransferLimits.REQUEST_RETRY_MILLIS) {
            return false;
        }
        if (pending.size() >= TextureTransferLimits.MAX_PENDING_REQUESTS) {
            Iterator<RequestKey> iterator = pending.keySet().iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
        pending.put(key, now);
        try {
            request.run();
            return true;
        } catch (RuntimeException e) {
            pending.remove(key);
            throw e;
        }
    }

    public synchronized void markFulfilled(String textureType, String hash) {
        if (NetworkSecurity.isValidTextureType(textureType) && NetworkSecurity.isValidContentId(hash)) {
            refreshSession();
            pending.remove(new RequestKey(textureType, hash));
        }
    }

    public synchronized void clear() {
        pending.clear();
        connectionIdentity = connection.get();
    }

    private void refreshSession() {
        Object current = connection.get();
        if (current != connectionIdentity) {
            pending.clear();
            connectionIdentity = current;
        }
    }

    private void purgeExpired(long now) {
        Iterator<Map.Entry<RequestKey, Long>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().getValue() >= TextureTransferLimits.REQUEST_RETRY_MILLIS) {
                iterator.remove();
            }
        }
    }

    private static final class RequestKey {
        private final String textureType;
        private final String hash;

        private RequestKey(String textureType, String hash) {
            this.textureType = textureType;
            this.hash = hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof RequestKey)) return false;
            RequestKey key = (RequestKey) other;
            return textureType.equals(key.textureType) && hash.equals(key.hash);
        }

        @Override
        public int hashCode() {
            return Objects.hash(textureType, hash);
        }
    }
}
