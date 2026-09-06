package com.quickskin.mod.server.data;

import com.quickskin.mod.networking.TextureTransferLimits;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerTextureResponseCoordinatorTest {

    @Test
    void isolatesConnectionsAndBoundsEachSession() {
        ServerTextureResponseCoordinator coordinator =
                new ServerTextureResponseCoordinator();
        UUID playerId = UUID.randomUUID();
        Object firstConnection = new Object();
        Object replacementConnection = new Object();

        assertNotNull(coordinator.reserve(playerId, firstConnection, 1));
        assertNotNull(coordinator.reserve(playerId, firstConnection, 1));
        assertNull(coordinator.reserve(playerId, firstConnection, 1));
        assertNotNull(coordinator.reserve(playerId, replacementConnection, 1));
    }

    @Test
    void cancellationKeepsTheLeaseUntilItsHolderReleasesBytes() {
        ServerTextureResponseCoordinator coordinator =
                new ServerTextureResponseCoordinator();
        UUID playerId = UUID.randomUUID();
        Object connection = new Object();
        ServerTextureResponseCoordinator.ResponseTicket ticket =
                coordinator.reserve(playerId, connection, 16);

        assertNotNull(ticket);
        assertFalse(coordinator.isCanceled(ticket));
        coordinator.cancelSession(playerId, connection);
        assertTrue(coordinator.isCanceled(ticket));

        coordinator.release(ticket);
        assertTrue(coordinator.isCanceled(ticket));
        assertNotNull(coordinator.reserve(playerId, connection, 16));
    }

    @Test
    void retainedByteBudgetIsReturnedExactlyOnce() {
        ServerTextureResponseCoordinator coordinator =
                new ServerTextureResponseCoordinator();
        List<ServerTextureResponseCoordinator.ResponseTicket> tickets =
                new ArrayList<>();
        int responseBytes = TextureTransferLimits.MAX_TEXTURE_BYTES;

        for (int index = 0; index < 8; index++) {
            ServerTextureResponseCoordinator.ResponseTicket ticket = coordinator.reserve(
                    UUID.randomUUID(), new Object(), responseBytes);
            assertNotNull(ticket);
            tickets.add(ticket);
        }
        assertNull(coordinator.reserve(UUID.randomUUID(), new Object(), 1));

        coordinator.release(tickets.get(0));
        coordinator.release(tickets.get(0));
        assertNotNull(coordinator.reserve(
                UUID.randomUUID(), new Object(), responseBytes));
    }
}
