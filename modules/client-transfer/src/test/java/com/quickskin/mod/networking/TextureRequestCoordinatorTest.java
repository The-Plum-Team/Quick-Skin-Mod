package com.quickskin.mod.networking;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureRequestCoordinatorTest {
    private static final String HASH = "a".repeat(40);

    @Test
    void equalButDifferentConnectionsMustNotSharePendingRequests() {
        AtomicReference<Object> connection = new AtomicReference<>(new String("same-server"));
        AtomicLong now = new AtomicLong(100L);
        AtomicInteger requests = new AtomicInteger();
        TextureRequestCoordinator coordinator = new TextureRequestCoordinator(connection::get, now::get);

        assertTrue(coordinator.requestIfNeeded("skin", HASH, requests::incrementAndGet));
        assertFalse(coordinator.requestIfNeeded("skin", HASH, requests::incrementAndGet));
        connection.set(new String("same-server"));
        assertTrue(coordinator.requestIfNeeded("skin", HASH, requests::incrementAndGet));
        assertEquals(2, requests.get());
    }

    @Test
    void retryAndFulfilmentRemainSpecificToTheTextureType() {
        Object connection = new Object();
        AtomicLong now = new AtomicLong(100L);
        TextureRequestCoordinator coordinator = new TextureRequestCoordinator(() -> connection, now::get);
        assertTrue(coordinator.requestIfNeeded("skin", HASH, () -> {}));
        assertTrue(coordinator.requestIfNeeded("cape", HASH, () -> {}));
        coordinator.markFulfilled("skin", HASH);
        assertTrue(coordinator.requestIfNeeded("skin", HASH, () -> {}));
        assertFalse(coordinator.requestIfNeeded("cape", HASH, () -> {}));
        now.addAndGet(TextureTransferLimits.REQUEST_RETRY_MILLIS);
        assertTrue(coordinator.requestIfNeeded("cape", HASH, () -> {}));
    }

    @Test
    void failedSubmissionDoesNotSuppressTheNextRequest() {
        Object connection = new Object();
        TextureRequestCoordinator coordinator = new TextureRequestCoordinator(() -> connection, () -> 100L);
        assertThrows(IllegalStateException.class, () -> coordinator.requestIfNeeded("skin", HASH,
                () -> { throw new IllegalStateException("disconnected"); }));
        assertTrue(coordinator.requestIfNeeded("skin", HASH, () -> {}));
    }
}
