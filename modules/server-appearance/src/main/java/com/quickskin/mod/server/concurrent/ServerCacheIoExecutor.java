package com.quickskin.mod.server.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded, lifecycle-owned worker for cache eviction filesystem cleanup. */
public final class ServerCacheIoExecutor implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerCacheIoExecutor.class);
    private static final ServerCacheIoExecutor INSTANCE =
            new ServerCacheIoExecutor(256, "QuickSkin-ServerCacheIo-");
    private static final long SHUTDOWN_TIMEOUT_MILLIS = 5_000L;

    private final int queueCapacity;
    private final String threadPrefix;
    private final AtomicInteger threadSequence = new AtomicInteger();
    private ThreadPoolExecutor activeExecutor;

    ServerCacheIoExecutor(int queueCapacity, String threadPrefix) {
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity must be positive");
        this.queueCapacity = queueCapacity;
        this.threadPrefix = threadPrefix;
    }

    public static ServerCacheIoExecutor getInstance() {
        return INSTANCE;
    }

    public synchronized void start() {
        if (activeExecutor != null && !activeExecutor.isShutdown()) return;
        ThreadFactory factory = task -> {
            Thread thread = new Thread(
                    task, threadPrefix + threadSequence.incrementAndGet());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, error) ->
                    LOGGER.error("Uncaught QuickSkin cache I/O failure", error));
            return thread;
        };
        activeExecutor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), factory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** Returns false when stopped or full; filesystem cleanup is never run on the caller thread. */
    public boolean submit(Runnable operation) {
        if (operation == null) return false;
        ThreadPoolExecutor executor;
        synchronized (this) {
            executor = activeExecutor;
            if (executor == null || executor.isShutdown()) return false;
        }
        try {
            executor.execute(() -> {
                try {
                    operation.run();
                } catch (RuntimeException | LinkageError error) {
                    LOGGER.warn("QuickSkin cache I/O cleanup failed", error);
                }
            });
            return true;
        } catch (RejectedExecutionException exception) {
            return false;
        }
    }

    /** Drains accepted cleanup before the cache directories lose their server-runtime identity. */
    @Override
    public void close() {
        ThreadPoolExecutor executor;
        synchronized (this) {
            executor = activeExecutor;
            activeExecutor = null;
        }
        if (executor == null) return;
        executor.shutdown();
        boolean interrupted = false;
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                List<Runnable> abandoned = executor.shutdownNow();
                if (!abandoned.isEmpty()) {
                    LOGGER.warn("Abandoned {} queued QuickSkin cache cleanup batches", abandoned.size());
                }
            }
        } catch (InterruptedException exception) {
            interrupted = true;
            List<Runnable> abandoned = executor.shutdownNow();
            if (!abandoned.isEmpty()) {
                LOGGER.warn("Abandoned {} interrupted QuickSkin cache cleanup batches", abandoned.size());
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
