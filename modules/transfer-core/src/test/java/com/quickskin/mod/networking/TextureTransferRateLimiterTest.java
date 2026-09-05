package com.quickskin.mod.networking;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureTransferRateLimiterTest {
    private final TextureTransferRateLimiter limiter =
            TextureTransferRateLimiter.getInstance();

    @AfterEach
    void clearLimiter() {
        limiter.clear();
    }

    @Test
    void capsOutboundTextureBytesPerConnectionWindow() {
        UUID player = UUID.randomUUID();
        Object connection = new Object();

        for (int index = 0; index < 4; index++) {
            assertTrue(limiter.allowDownloadBytes(
                    player, connection, TextureTransferLimits.MAX_TEXTURE_BYTES));
        }
        assertFalse(limiter.allowDownloadBytes(
                player, connection, TextureTransferLimits.MAX_TEXTURE_BYTES));
    }

    @Test
    void isolatesRateStateByConnectionIdentity() {
        UUID player = UUID.randomUUID();
        Object firstConnection = new Object();
        Object secondConnection = new Object();

        for (int index = 0; index < 4; index++) {
            assertTrue(limiter.allowDownloadBytes(
                    player, firstConnection, TextureTransferLimits.MAX_TEXTURE_BYTES));
        }
        assertFalse(limiter.allowDownloadBytes(
                player, firstConnection, TextureTransferLimits.MAX_TEXTURE_BYTES));
        assertTrue(limiter.allowDownloadBytes(
                player, secondConnection, TextureTransferLimits.MAX_TEXTURE_BYTES));
    }

    @Test
    void oldDisconnectCleanupPreservesRapidReconnectState() {
        UUID player = UUID.randomUUID();
        Object oldConnection = new Object();
        Object newConnection = new Object();

        for (int index = 0; index < 4; index++) {
            assertTrue(limiter.allowDownloadBytes(
                    player, newConnection, TextureTransferLimits.MAX_TEXTURE_BYTES));
        }
        limiter.allowTextureRequest(player, oldConnection);
        limiter.removeSession(player, oldConnection);

        assertFalse(limiter.allowDownloadBytes(
                player, newConnection, TextureTransferLimits.MAX_TEXTURE_BYTES));
    }

    @Test
    void failedResponseReservationCanBeRefundedOnlyOnce() {
        UUID player = UUID.randomUUID();
        Object connection = new Object();
        TextureTransferRateLimiter.DownloadReservation reservation =
                limiter.reserveDownloadBytes(
                        player, connection, TextureTransferLimits.MAX_TEXTURE_BYTES);
        assertTrue(reservation != null);
        limiter.refundDownloadBytes(reservation);
        limiter.refundDownloadBytes(reservation);

        for (int index = 0; index < 4; index++) {
            assertTrue(limiter.allowDownloadBytes(
                    player, connection, TextureTransferLimits.MAX_TEXTURE_BYTES));
        }
        assertFalse(limiter.allowDownloadBytes(
                player, connection, TextureTransferLimits.MAX_TEXTURE_BYTES));
    }

    @Test
    void capsDecodedPixelsPerAuthenticatedConnection() {
        UUID player = UUID.randomUUID();
        Object connection = new Object();

        assertTrue(limiter.allowDecodedPixels(
                player, connection, TextureTransferLimits.MAX_IMAGE_PIXELS));
        assertTrue(limiter.allowDecodedPixels(
                player, connection, TextureTransferLimits.MAX_IMAGE_PIXELS));
        assertFalse(limiter.allowDecodedPixels(player, connection, 1));
    }

    @Test
    void rejectsInvalidPixelReservationsAndIsolatesReconnects() {
        UUID player = UUID.randomUUID();
        Object firstConnection = new Object();
        Object secondConnection = new Object();

        assertFalse(limiter.allowDecodedPixels(player, firstConnection, 0));
        assertFalse(limiter.allowDecodedPixels(
                player, firstConnection, TextureTransferLimits.MAX_IMAGE_PIXELS + 1));
        assertTrue(limiter.allowDecodedPixels(
                player, firstConnection, TextureTransferLimits.MAX_IMAGE_PIXELS));
        assertTrue(limiter.allowDecodedPixels(
                player, secondConnection, TextureTransferLimits.MAX_IMAGE_PIXELS));
    }

    @Test
    void boundsSnapshotRetriesAndKeepsReconnectStateIndependent() {
        UUID player = UUID.randomUUID();
        Object firstConnection = new Object();
        Object replacementConnection = new Object();

        for (int index = 0; index < 4; index++) {
            assertTrue(limiter.allowAppearanceSnapshotRequest(player, firstConnection));
        }
        assertFalse(limiter.allowAppearanceSnapshotRequest(player, firstConnection));
        assertTrue(limiter.allowAppearanceSnapshotRequest(player, replacementConnection));

        limiter.removeSession(player, firstConnection);
        assertTrue(limiter.allowAppearanceSnapshotRequest(player, firstConnection));
        assertTrue(limiter.allowAppearanceSnapshotRequest(player, replacementConnection));
    }

    @Test
    void helloAdmissionIsABoundedExactConnectionBudget() {
        UUID player = UUID.randomUUID();
        Object firstConnection = new Object();
        Object replacementConnection = new Object();

        for (int attempt = 0; attempt < 5; attempt++) {
            assertTrue(limiter.allowProtocolHello(player, firstConnection));
        }
        assertFalse(limiter.allowProtocolHello(player, firstConnection));
        assertTrue(limiter.allowProtocolHello(player, replacementConnection));

        limiter.removeSession(player, firstConnection);
        assertTrue(limiter.allowProtocolHello(player, firstConnection));
        assertTrue(limiter.allowProtocolHello(player, replacementConnection));
    }
}
