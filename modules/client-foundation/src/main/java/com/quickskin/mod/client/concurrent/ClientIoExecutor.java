package com.quickskin.mod.client.concurrent;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Process-owned, bounded executor for blocking client file and image work. */
@Environment(EnvType.CLIENT)
public final class ClientIoExecutor {
    private static final int THREADS = 4;
    private static final int QUEUED_TASKS = 64;
    private static final long MAX_RETAINED_TASK_BYTES = 64L * 1024L * 1024L;
    private static final AtomicInteger WORKER_IDS = new AtomicInteger();
    private static final AtomicLong RETAINED_TASK_BYTES = new AtomicLong();
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            THREADS,
            THREADS,
            30L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(QUEUED_TASKS),
            task -> {
                Thread thread = new Thread(task,
                        "quickskin-client-io-" + WORKER_IDS.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());

    static {
        EXECUTOR.allowCoreThreadTimeOut(true);
    }

    private ClientIoExecutor() {
    }

    public static CompletableFuture<Void> runAsync(Runnable task) {
        return supplyAsync(() -> {
            task.run();
            return null;
        });
    }

    public static <T> CompletableFuture<T> supplyAsync(Supplier<T> task) {
        return supplyAsyncRetaining(0L, task);
    }

    /**
     * Schedules work while keeping a global lease for large input objects captured by the task.
     * The lease bridges queue admission until the worker has consumed the source; result owners
     * must apply their own handoff accounting when decoded/native data outlives the worker.
     */
    public static <T> CompletableFuture<T> supplyAsyncRetaining(
            long retainedBytes, Supplier<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (task == null || retainedBytes < 0L
                || !reserveRetainedBytes(retainedBytes)) {
            future.completeExceptionally(new RejectedExecutionException(
                    "QuickSkin client I/O retained-input limit reached"));
            return future;
        }
        try {
            EXECUTOR.execute(() -> {
                try {
                    if (!future.isCancelled()) future.complete(task.get());
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                } finally {
                    if (retainedBytes > 0L) RETAINED_TASK_BYTES.addAndGet(-retainedBytes);
                }
            });
        } catch (RejectedExecutionException error) {
            if (retainedBytes > 0L) RETAINED_TASK_BYTES.addAndGet(-retainedBytes);
            future.completeExceptionally(error);
        }
        return future;
    }

    private static boolean reserveRetainedBytes(long retainedBytes) {
        if (retainedBytes == 0L) return true;
        if (retainedBytes > MAX_RETAINED_TASK_BYTES) return false;
        while (true) {
            long current = RETAINED_TASK_BYTES.get();
            if (current > MAX_RETAINED_TASK_BYTES - retainedBytes) return false;
            if (RETAINED_TASK_BYTES.compareAndSet(current, current + retainedBytes)) return true;
        }
    }

    public static void close() {
        EXECUTOR.shutdownNow();
    }
}
