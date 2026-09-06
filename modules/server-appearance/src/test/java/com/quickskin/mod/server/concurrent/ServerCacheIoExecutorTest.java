package com.quickskin.mod.server.concurrent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerCacheIoExecutorTest {

    @Test
    void queueIsBoundedAndNeverFallsBackToTheCaller() throws Exception {
        ServerCacheIoExecutor executor = new ServerCacheIoExecutor(1, "test-cache-io-");
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        executor.start();
        try {
            assertTrue(executor.submit(() -> {
                running.countDown();
                await(release);
                calls.incrementAndGet();
            }));
            assertTrue(running.await(2, TimeUnit.SECONDS));
            assertTrue(executor.submit(calls::incrementAndGet));
            assertFalse(executor.submit(calls::incrementAndGet));
        } finally {
            release.countDown();
            executor.close();
        }
        assertEquals(2, calls.get());
    }

    @Test
    void workerFailureDoesNotPreventLaterCleanup() {
        ServerCacheIoExecutor executor = new ServerCacheIoExecutor(2, "test-cache-failure-");
        AtomicInteger calls = new AtomicInteger();
        executor.start();
        assertTrue(executor.submit(() -> { throw new IllegalStateException("expected"); }));
        assertTrue(executor.submit(calls::incrementAndGet));
        executor.close();
        assertEquals(1, calls.get());
    }

    @Test
    void shutdownDrainsAcceptedWorkAndRejectsNewHandoffs() {
        ServerCacheIoExecutor executor = new ServerCacheIoExecutor(2, "test-cache-close-");
        AtomicInteger calls = new AtomicInteger();
        executor.start();
        assertTrue(executor.submit(calls::incrementAndGet));
        executor.close();

        assertEquals(1, calls.get());
        assertFalse(executor.submit(calls::incrementAndGet));
        assertEquals(1, calls.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for test release");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
