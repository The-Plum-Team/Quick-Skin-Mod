package com.quickskin.mod.networking.protocol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Exact-connection protocol state. No UUID-only lookup or replacement-session cleanup is used. */
public final class ProtocolSessions {
    private static final int MAX_SERVER_SESSIONS = 4096;
    /** Initial acknowledgement plus the client's four possible hello retries. */
    private static final int MAX_ACKNOWLEDGEMENTS_PER_HELLO = 5;
    private static final ProtocolSessions INSTANCE = new ProtocolSessions();

    private final AtomicLong nonces = new AtomicLong();
    private final Map<SessionKey, ServerSession> serverProfiles = new LinkedHashMap<>();
    private ClientSession clientSession;

    private ProtocolSessions() {
    }

    public static ProtocolSessions getInstance() {
        return INSTANCE;
    }

    public synchronized ClientHello beginClientSession(
            UUID playerId, Object connection,
            boolean helloChannelAvailable, boolean legacyChannelAvailable) {
        if (playerId == null || connection == null) return null;
        long nonce = nonces.incrementAndGet();
        if (nonce <= 0L) {
            nonces.set(1L);
            nonce = 1L;
        }
        ProtocolProfile initial;
        boolean sendHello;
        if (helloChannelAvailable) {
            initial = ProtocolProfile.localOnly("awaiting-protocol-ack");
            sendHello = true;
        } else if (legacyChannelAvailable) {
            initial = ProtocolProfile.legacy("legacy-channel-confirmed");
            sendHello = false;
        } else {
            initial = ProtocolProfile.localOnly("quickskin-channel-unavailable");
            sendHello = false;
        }
        clientSession = new ClientSession(playerId, connection, nonce, initial);
        return new ClientHello(nonce, QuickSkinProtocol.POLICY.offer(), sendHello);
    }

    public synchronized ProtocolProfile acceptClientAcknowledgement(
            UUID playerId, Object connection, long nonce,
            ProtocolAcknowledgement acknowledgement) {
        ClientSession session = clientSession;
        if (session == null || !session.playerId.equals(playerId)
                || session.connection != connection || session.nonce != nonce
                || session.profile.mode() != ProtocolProfile.Mode.LOCAL_ONLY) {
            return ProtocolProfile.incompatible("stale-protocol-ack");
        }
        ProtocolProfile profile = ProtocolNegotiator.verifyAcknowledgement(
                QuickSkinProtocol.POLICY, acknowledgement);
        clientSession = new ClientSession(playerId, connection, nonce, profile);
        return profile;
    }

    public synchronized ProtocolProfile clientProfile(Object connection) {
        ClientSession session = clientSession;
        return session != null && session.connection == connection
                ? session.profile : ProtocolProfile.localOnly("no-exact-client-session");
    }

    /**
     * Restores the recorded wire mode for ReplayMod's exact fake connection.
     *
     * <p>Replay files contain only the server-bound half of the exchange, so the original client
     * hello is unavailable during playback. The first recorded Quick Skin data packet is explicit
     * schema evidence. It may establish one locally bounded profile, but it cannot switch schemas
     * on an already classified replay connection.</p>
    */
    public synchronized ProtocolProfile admitReplayClientSession(
            UUID playerId, Object connection, boolean v2) {
        if (playerId == null || connection == null) {
            return ProtocolProfile.incompatible("invalid-replay-session");
        }
        ProtocolProfile requested = v2
                ? ProtocolNegotiator.negotiate(
                        QuickSkinProtocol.POLICY, QuickSkinProtocol.POLICY.offer())
                : ProtocolProfile.legacy("recorded-legacy-packet");
        ClientSession existing = clientSession;
        if (existing != null && existing.connection == connection) {
            if (!existing.playerId.equals(playerId)) {
                return ProtocolProfile.incompatible("replay-player-switch");
            }
            return existing.profile.mode() == requested.mode()
                    ? existing.profile
                    : ProtocolProfile.incompatible("replay-schema-switch");
        }
        clientSession = new ClientSession(playerId, connection, 0L, requested);
        return requested;
    }

    public synchronized void clearClientSession(UUID playerId, Object connection) {
        ClientSession session = clientSession;
        if (session == null || session.connection != connection
                || (playerId != null && !session.playerId.equals(playerId))) return;
        clientSession = null;
    }

    /** Negotiates one hello; an existing legacy or negotiated mode cannot be switched in place. */
    public synchronized ServerHelloResult acceptServerHello(
            UUID playerId, Object connection, long nonce, ProtocolOffer offer) {
        SessionKey key = SessionKey.of(playerId, connection);
        if (key == null) return new ServerHelloResult(
                nonce, ProtocolAcknowledgement.rejected(),
                ProtocolProfile.incompatible("invalid-session"), false, false);
        ServerSession existing = serverProfiles.get(key);
        boolean replacePassiveLegacy = existing != null
                && existing.passiveLegacy
                && existing.profile.mode() == ProtocolProfile.Mode.LEGACY_V1;
        if (existing != null && !replacePassiveLegacy) {
            if (existing.nonce == nonce) {
                boolean shouldAcknowledge = existing.acknowledgements
                        < MAX_ACKNOWLEDGEMENTS_PER_HELLO;
                if (shouldAcknowledge) {
                    serverProfiles.put(
                            key,
                            new ServerSession(
                                    existing.nonce,
                                    existing.acknowledgement,
                                    existing.profile,
                                    existing.passiveLegacy,
                                    existing.acknowledgements + 1));
                }
                return new ServerHelloResult(
                        nonce, existing.acknowledgement, existing.profile,
                        false, shouldAcknowledge);
            }
            return new ServerHelloResult(
                    nonce, ProtocolAcknowledgement.rejected(), existing.profile, false, false);
        }
        if (existing == null && serverProfiles.size() >= MAX_SERVER_SESSIONS) {
            return new ServerHelloResult(
                    nonce, ProtocolAcknowledgement.rejected(),
                    ProtocolProfile.incompatible("session-capacity"), false, false);
        }
        ProtocolProfile profile = ProtocolNegotiator.negotiate(QuickSkinProtocol.POLICY, offer);
        ProtocolAcknowledgement acknowledgement = profile.negotiated()
                ? ProtocolAcknowledgement.accepted(profile)
                : ProtocolAcknowledgement.rejected();
        if (replacePassiveLegacy && !profile.negotiated()) {
            boolean sameRejectedHello = existing.nonce == nonce
                    && !existing.acknowledgement.accepted();
            int acknowledgements = sameRejectedHello
                    ? existing.acknowledgements : 0;
            boolean shouldAcknowledge = acknowledgements
                    < MAX_ACKNOWLEDGEMENTS_PER_HELLO;
            serverProfiles.put(
                    key,
                    new ServerSession(
                            nonce,
                            acknowledgement,
                            existing.profile,
                            true,
                            acknowledgements + (shouldAcknowledge ? 1 : 0)));
            return new ServerHelloResult(
                    nonce, acknowledgement, profile, false, shouldAcknowledge);
        }
        serverProfiles.put(
                key, new ServerSession(nonce, acknowledgement, profile, false, 1));
        return new ServerHelloResult(
                nonce, acknowledgement, profile, profile.negotiated(), true);
    }

    /** A packet on a registered v1 channel is explicit evidence of the legacy schema. */
    public synchronized LegacyAdmission acceptLegacyClient(UUID playerId, Object connection) {
        SessionKey key = SessionKey.of(playerId, connection);
        if (key == null) return new LegacyAdmission(false, false);
        ServerSession existing = serverProfiles.get(key);
        if (existing != null) {
            if (existing.passiveLegacy
                    && existing.profile.mode() == ProtocolProfile.Mode.LEGACY_V1) {
                serverProfiles.put(
                        key,
                        new ServerSession(
                                existing.nonce, existing.acknowledgement,
                                existing.profile, false, existing.acknowledgements));
            }
            return new LegacyAdmission(
                    existing.profile.mode() == ProtocolProfile.Mode.LEGACY_V1, false);
        }
        if (serverProfiles.size() >= MAX_SERVER_SESSIONS) {
            return new LegacyAdmission(false, false);
        }
        ProtocolProfile legacy = ProtocolProfile.legacy("legacy-packet-received");
        serverProfiles.put(
                key, new ServerSession(
                        0L, ProtocolAcknowledgement.rejected(), legacy, false, 0));
        return new LegacyAdmission(true, true);
    }

    /** Records explicit legacy channel presence without blocking a later stronger hello. */
    public synchronized boolean classifyLegacyClient(UUID playerId, Object connection) {
        SessionKey key = SessionKey.of(playerId, connection);
        if (key == null) return false;
        ServerSession existing = serverProfiles.get(key);
        if (existing != null) return existing.profile.mode() == ProtocolProfile.Mode.LEGACY_V1;
        if (serverProfiles.size() >= MAX_SERVER_SESSIONS) return false;
        ProtocolProfile legacy = ProtocolProfile.legacy("legacy-channel-confirmed");
        serverProfiles.put(
                key, new ServerSession(
                        0L, ProtocolAcknowledgement.rejected(), legacy, true, 0));
        return true;
    }

    public synchronized ProtocolProfile serverProfile(UUID playerId, Object connection) {
        SessionKey key = SessionKey.of(playerId, connection);
        ServerSession session = key == null ? null : serverProfiles.get(key);
        ProtocolProfile profile = session == null ? null : session.profile;
        return profile != null ? profile : ProtocolProfile.localOnly("no-exact-server-session");
    }

    public synchronized void removeServerSession(UUID playerId, Object connection) {
        SessionKey key = SessionKey.of(playerId, connection);
        if (key != null) serverProfiles.remove(key);
    }

    public synchronized int serverSessionCount() {
        return serverProfiles.size();
    }

    public synchronized void clearServerSessions() {
        serverProfiles.clear();
    }

    public record ClientHello(long nonce, ProtocolOffer offer, boolean sendHello) {
    }

    public record ServerHelloResult(
            long nonce,
            ProtocolAcknowledgement acknowledgement,
            ProtocolProfile profile,
            boolean becameReady,
            boolean shouldAcknowledge) {
    }

    public record LegacyAdmission(boolean accepted, boolean becameReady) {
    }

    private record ClientSession(
            UUID playerId, Object connection, long nonce, ProtocolProfile profile) {
    }

    private record ServerSession(
            long nonce,
            ProtocolAcknowledgement acknowledgement,
            ProtocolProfile profile,
            boolean passiveLegacy,
            int acknowledgements) {
    }

    private static final class SessionKey {
        private final UUID playerId;
        private final Object connection;

        private SessionKey(UUID playerId, Object connection) {
            this.playerId = playerId;
            this.connection = connection;
        }

        private static SessionKey of(UUID playerId, Object connection) {
            return playerId == null || connection == null ? null : new SessionKey(playerId, connection);
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof SessionKey key
                    && playerId.equals(key.playerId) && connection == key.connection;
        }

        @Override
        public int hashCode() {
            return 31 * playerId.hashCode() + System.identityHashCode(connection);
        }
    }
}
