package com.quickskin.mod.server.data;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerUploadCoordinatorTest {
    private static final String HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test
    void isolatesExactConnectionAndReferencedHash() {
        AtomicLong now = new AtomicLong();
        ServerUploadCoordinator coordinator = new ServerUploadCoordinator(now::get);
        UUID playerId = UUID.randomUUID();
        Object firstConnection = new Object();
        Object secondConnection = new Object();
        ServerUploadCoordinator.UploadTicket ticket = coordinator.beginUpload(
                playerId, firstConnection, "skin", HASH_A, 1);
        assertNotNull(ticket);

        ServerUploadCoordinator.PendingAppearance appearance = appearance(HASH_A, null);
        assertFalse(coordinator.deferAppearance(
                playerId, secondConnection, appearance, HASH_A, null));
        assertFalse(coordinator.deferAppearance(
                playerId, firstConnection, appearance(HASH_B, null), HASH_B, null));
        assertTrue(coordinator.deferAppearance(
                playerId, firstConnection, appearance, HASH_A, null));

        ServerUploadCoordinator.RetryBatch retry = coordinator.finishUpload(ticket);
        assertEquals(appearance, retry.appearance());
        assertEquals(0, coordinator.sessionCount());
    }

    @Test
    void waitsForEveryMissingReferencedTexture() {
        ServerUploadCoordinator coordinator =
                new ServerUploadCoordinator(() -> 1L);
        UUID playerId = UUID.randomUUID();
        Object connection = new Object();
        ServerUploadCoordinator.UploadTicket skin = coordinator.beginUpload(
                playerId, connection, "skin", HASH_A, 1);
        ServerUploadCoordinator.UploadTicket cape = coordinator.beginUpload(
                playerId, connection, "cape", HASH_B, 1);
        ServerUploadCoordinator.PendingAppearance appearance = appearance(HASH_A, HASH_B);

        assertTrue(coordinator.deferAppearance(
                playerId, connection, appearance, HASH_A, HASH_B));
        ServerUploadCoordinator.RetryBatch afterSkin = coordinator.finishUpload(skin);
        assertEquals(appearance, afterSkin.appearance());

        // The handler has committed the skin before this retry, so only the cape is missing.
        assertTrue(coordinator.deferAppearance(
                playerId, connection, appearance, null, HASH_B));
        assertEquals(appearance, coordinator.finishUpload(cape).appearance());
    }

    @Test
    void latestAppearanceSupersedesOlderDeferredValue() {
        ServerUploadCoordinator coordinator =
                new ServerUploadCoordinator(() -> 1L);
        UUID playerId = UUID.randomUUID();
        Object connection = new Object();
        ServerUploadCoordinator.UploadTicket ticket = coordinator.beginUpload(
                playerId, connection, "skin", null, 1);
        ServerUploadCoordinator.PendingAppearance first = appearance(HASH_A, null);
        ServerUploadCoordinator.PendingAppearance latest = appearance(HASH_B, null);

        assertTrue(coordinator.deferAppearance(
                playerId, connection, first, HASH_A, null));
        coordinator.supersedeAppearance(playerId, connection);
        assertTrue(coordinator.deferAppearance(
                playerId, connection, latest, HASH_B, null));
        coordinator.identifyUpload(ticket, HASH_B);

        assertEquals(latest, coordinator.finishUpload(ticket).appearance());
    }

    @Test
    void metadataIsLatestPerHashAndBounded() {
        ServerUploadCoordinator coordinator =
                new ServerUploadCoordinator(() -> 1L);
        UUID playerId = UUID.randomUUID();
        Object connection = new Object();
        ServerUploadCoordinator.UploadTicket ticket = coordinator.beginUpload(
                playerId, connection, "cape", null, 1);

        for (int index = 0;
                index < ServerUploadCoordinator.MAX_PENDING_METADATA_PER_SESSION + 1;
                index++) {
            String hash = String.format("%040x", index + 1);
            assertTrue(coordinator.deferMetadata(
                    playerId, connection,
                    new ServerUploadCoordinator.PendingMetadata(hash, "metadata-" + index)));
        }
        ServerUploadCoordinator.PendingMetadata latest =
                new ServerUploadCoordinator.PendingMetadata(HASH_B, "latest");
        assertTrue(coordinator.deferMetadata(playerId, connection, latest));
        coordinator.identifyUpload(ticket, HASH_B);

        ServerUploadCoordinator.RetryBatch retry = coordinator.finishUpload(ticket);
        assertEquals(ServerUploadCoordinator.MAX_PENDING_METADATA_PER_SESSION,
                retry.metadata().size());
        assertTrue(retry.metadata().contains(latest));
    }

    @Test
    void pendingValuesExpireWithoutPurgingActiveTicket() {
        AtomicLong now = new AtomicLong();
        ServerUploadCoordinator coordinator = new ServerUploadCoordinator(now::get);
        UUID playerId = UUID.randomUUID();
        Object connection = new Object();
        ServerUploadCoordinator.UploadTicket ticket = coordinator.beginUpload(
                playerId, connection, "skin", HASH_A, 1);
        assertTrue(coordinator.deferAppearance(
                playerId, connection, appearance(HASH_A, null), HASH_A, null));

        now.set(ServerUploadCoordinator.PENDING_TTL_MILLIS + 1);
        assertEquals(1, coordinator.sessionCount());
        ServerUploadCoordinator.RetryBatch retry = coordinator.finishUpload(ticket);
        assertNull(retry.appearance());
        assertTrue(retry.metadata().isEmpty());
        assertEquals(0, coordinator.sessionCount());
    }

    @Test
    void staleTicketStopsConsumingPerSessionSlotsButKeepsLeaseUntilFinished() {
        AtomicLong now = new AtomicLong();
        ServerUploadCoordinator coordinator = new ServerUploadCoordinator(now::get);
        UUID playerId = UUID.randomUUID();
        Object connection = new Object();
        ServerUploadCoordinator.UploadTicket stale = coordinator.beginUpload(
                playerId, connection, "skin", HASH_A, 1);

        now.set(ServerUploadCoordinator.UPLOAD_TICKET_TTL_MILLIS + 1);
        assertTrue(coordinator.isCanceled(stale));
        assertNotNull(coordinator.beginUpload(
                playerId, connection, "skin", HASH_B, 1));
        coordinator.finishUpload(stale);
    }

    private static ServerUploadCoordinator.PendingAppearance appearance(
            String skinHash, String capeHash) {
        return new ServerUploadCoordinator.PendingAppearance(
                skinHash == null ? "" : "local_skin:" + skinHash,
                capeHash == null ? "" : "local_cape:" + capeHash,
                "classic");
    }
}
