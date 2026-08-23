package com.quickskin.mod.networking.protocol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolSessionsTest {
    private final ProtocolSessions sessions = ProtocolSessions.getInstance();

    @AfterEach
    void clearSessions() {
        sessions.clearServerSessions();
        // Client cleanup uses the exact values supplied by each test.
    }

    @Test
    void clientAcknowledgementIsBoundToUuidConnectionAndNonce() {
        UUID playerId = UUID.randomUUID();
        Object connection = new Object();
        ProtocolSessions.ClientHello hello = sessions.beginClientSession(
                playerId, connection, true, true);
        ProtocolProfile negotiated = ProtocolNegotiator.negotiate(
                QuickSkinProtocol.POLICY, QuickSkinProtocol.POLICY.offer());
        ProtocolAcknowledgement acknowledgement =
                ProtocolAcknowledgement.accepted(negotiated);

        assertEquals(ProtocolProfile.Mode.INCOMPATIBLE,
                sessions.acceptClientAcknowledgement(
                        playerId, new Object(), hello.nonce(), acknowledgement).mode());
        assertEquals(ProtocolProfile.Mode.INCOMPATIBLE,
                sessions.acceptClientAcknowledgement(
                        playerId, connection, hello.nonce() + 1L, acknowledgement).mode());
        assertTrue(sessions.acceptClientAcknowledgement(
                playerId, connection, hello.nonce(), acknowledgement).negotiated());

        sessions.clearClientSession(playerId, connection);
    }

    @Test
    void legacyEvidenceCannotDowngradeANegotiatedExactSession() {
        UUID playerId = UUID.randomUUID();
        Object connection = new Object();
        ProtocolSessions.ServerHelloResult result = sessions.acceptServerHello(
                playerId, connection, 7L, QuickSkinProtocol.POLICY.offer());

        assertTrue(result.profile().negotiated());
        assertFalse(sessions.acceptLegacyClient(playerId, connection).accepted());
        assertEquals(ProtocolProfile.Mode.NEGOTIATED,
                sessions.serverProfile(playerId, connection).mode());

        sessions.removeServerSession(playerId, new Object());
        assertEquals(1, sessions.serverSessionCount());
        sessions.removeServerSession(playerId, connection);
        assertEquals(0, sessions.serverSessionCount());
    }

    @Test
    void passiveLegacyChannelClassificationMayUpgradeBeforeAnyLegacyPacket() {
        UUID playerId = UUID.randomUUID();
        Object connection = new Object();

        assertTrue(sessions.classifyLegacyClient(playerId, connection));
        ProtocolSessions.ServerHelloResult result = sessions.acceptServerHello(
                playerId, connection, 19L, QuickSkinProtocol.POLICY.offer());

        assertTrue(result.profile().negotiated());
        assertTrue(result.becameReady());
        assertTrue(result.shouldAcknowledge());
    }

    @Test
    void missingHelloFallsBackOnlyWhenLegacyChannelsWereExplicitlyPresent() {
        UUID playerId = UUID.randomUUID();
        Object legacyConnection = new Object();
        Object vanillaConnection = new Object();

        sessions.beginClientSession(playerId, legacyConnection, false, true);
        assertEquals(ProtocolProfile.Mode.LEGACY_V1,
                sessions.clientProfile(legacyConnection).mode());
        sessions.clearClientSession(playerId, legacyConnection);

        sessions.beginClientSession(playerId, vanillaConnection, false, false);
        assertEquals(ProtocolProfile.Mode.LOCAL_ONLY,
                sessions.clientProfile(vanillaConnection).mode());
        sessions.clearClientSession(playerId, vanillaConnection);
    }

    @Test
    void acknowledgementReplayIsBoundedForOneExactHello() {
        // A received hello is the ACK-channel evidence on legacy Forge, where the channel query
        // can return a false negative. The replay cap makes that deliberate exception bounded.
        UUID playerId = UUID.randomUUID();
        Object connection = new Object();
        long nonce = 41L;

        ProtocolSessions.ServerHelloResult initial = sessions.acceptServerHello(
                playerId, connection, nonce, QuickSkinProtocol.POLICY.offer());
        assertTrue(initial.shouldAcknowledge());
        for (int retry = 0; retry < 4; retry++) {
            assertTrue(sessions.acceptServerHello(
                    playerId, connection, nonce,
                    QuickSkinProtocol.POLICY.offer()).shouldAcknowledge());
        }
        assertFalse(sessions.acceptServerHello(
                playerId, connection, nonce,
                QuickSkinProtocol.POLICY.offer()).shouldAcknowledge());
        assertTrue(sessions.serverProfile(playerId, connection).negotiated());
    }

    @Test
    void incompatibleHelloDoesNotErasePassiveLegacyEvidence() {
        UUID playerId = UUID.randomUUID();
        Object connection = new Object();
        ProtocolOffer incompatible = new ProtocolOffer(
                2, 2, 0L, 1024, 512);

        assertTrue(sessions.classifyLegacyClient(playerId, connection));
        ProtocolSessions.ServerHelloResult rejected = sessions.acceptServerHello(
                playerId, connection, 73L, incompatible);

        assertFalse(rejected.profile().negotiated());
        assertTrue(rejected.shouldAcknowledge());
        assertEquals(
                ProtocolProfile.Mode.LEGACY_V1,
                sessions.serverProfile(playerId, connection).mode());
        for (int retry = 0; retry < 4; retry++) {
            assertTrue(sessions.acceptServerHello(
                    playerId, connection, 73L, incompatible).shouldAcknowledge());
        }
        assertFalse(sessions.acceptServerHello(
                playerId, connection, 73L, incompatible).shouldAcknowledge());
        assertEquals(
                ProtocolProfile.Mode.LEGACY_V1,
                sessions.serverProfile(playerId, connection).mode());

        ProtocolSessions.ServerHelloResult upgraded = sessions.acceptServerHello(
                playerId, connection, 74L, QuickSkinProtocol.POLICY.offer());
        assertTrue(upgraded.profile().negotiated());
        assertTrue(upgraded.becameReady());
    }

    @Test
    void sameUuidReplacementDoesNotInheritOrLoseExactConnectionState() {
        UUID playerId = UUID.randomUUID();
        Object oldConnection = new Object();
        Object replacementConnection = new Object();

        assertTrue(sessions.acceptServerHello(
                playerId, oldConnection, 91L,
                QuickSkinProtocol.POLICY.offer()).profile().negotiated());
        assertEquals(
                ProtocolProfile.Mode.LOCAL_ONLY,
                sessions.serverProfile(playerId, replacementConnection).mode());

        assertTrue(sessions.classifyLegacyClient(playerId, replacementConnection));
        sessions.removeServerSession(playerId, oldConnection);

        assertEquals(
                ProtocolProfile.Mode.LEGACY_V1,
                sessions.serverProfile(playerId, replacementConnection).mode());
        assertEquals(1, sessions.serverSessionCount());
    }

    @Test
    void replaySchemaEvidenceCreatesOneExactBoundedClientProfile() {
        UUID playerId = UUID.randomUUID();
        Object replayConnection = new Object();

        assertTrue(sessions.admitReplayClientSession(
                playerId, replayConnection, true).negotiated());
        assertTrue(sessions.clientProfile(replayConnection).negotiated());
        assertEquals(
                ProtocolProfile.Mode.LOCAL_ONLY,
                sessions.clientProfile(new Object()).mode());
        assertEquals(
                ProtocolProfile.Mode.INCOMPATIBLE,
                sessions.admitReplayClientSession(
                        playerId, replayConnection, false).mode());
        assertEquals(
                ProtocolProfile.Mode.INCOMPATIBLE,
                sessions.admitReplayClientSession(
                        UUID.randomUUID(), replayConnection, true).mode());
        assertTrue(sessions.clientProfile(replayConnection).negotiated());

        sessions.clearClientSession(playerId, replayConnection);
    }
}
