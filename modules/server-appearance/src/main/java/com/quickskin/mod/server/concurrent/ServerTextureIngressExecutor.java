package com.quickskin.mod.server.concurrent;

import com.quickskin.mod.networking.TextureTransferLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded workers for untrusted server texture ingress.
 *
 * <p>Image decoding and bulk temporary-file writes must not run on the server thread. Both the
 * task count and the encoded bytes retained by queued/running work are bounded so a burst of
 * completed chunk assemblies cannot simply move the memory pressure into an executor queue.</p>
 */
public final class ServerTextureIngressExecutor implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerTextureIngressExecutor.class);
    private static final ServerTextureIngressExecutor INSTANCE = new ServerTextureIngressExecutor();
    private static final int WORKERS = 2;
    private static final int MAX_QUEUED_TASKS = 32;
    private static final long MAX_RETAINED_BYTES =
            TextureTransferLimits.MAX_SERVER_ASSEMBLY_BYTES;

    private final AtomicInteger threadSequence = new AtomicInteger();
    private ExecutorState activeState;

    private ServerTextureIngressExecutor() {
    }

    public static ServerTextureIngressExecutor getInstance() {
        return INSTANCE;
    }

    /** Starts a fresh executor for one server runtime. */
    public synchronized void start() {
        if (activeState != null && !activeState.executor.isShutdown()) return;

        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(
                    task, "QuickSkin-ServerTextureIngress-" + threadSequence.incrementAndGet());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, error) ->
                    LOGGER.error("Uncaught QuickSkin server texture ingress failure", error));
            return thread;
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                WORKERS,
                WORKERS,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUED_TASKS),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
        activeState = new ExecutorState(executor);
    }

    /**
     * Submits work while reserving the encoded bytes retained by it.
     *
     * @return {@code false} when the server runtime is stopped or either bound is exhausted
     */
    public boolean submit(int retainedBytes, Runnable task) {
        return submit(retainedBytes, task, () -> { });
    }

    /**
     * Variant with a cleanup callback for work removed from the queue during server shutdown.
     * The abandoned task's captured byte array is released before the callback runs.
     */
    public boolean submit(int retainedBytes, Runnable task, Runnable onAbandoned) {
        if (task == null || retainedBytes <= 0
                || retainedBytes > TextureTransferLimits.MAX_TEXTURE_BYTES
                || onAbandoned == null) return false;

        ExecutorState state;
        synchronized (this) {
            state = activeState;
            if (state == null || state.executor.isShutdown()) return false;
        }
        if (!reserveBytes(state.retainedBytes, retainedBytes)) return false;

        RetainedTask retainedTask = new RetainedTask(
                task, onAbandoned, state.retainedBytes, retainedBytes);
        try {
            state.executor.execute(retainedTask);
            return true;
        } catch (RejectedExecutionException exception) {
            retainedTask.rejectBeforeEnqueue();
            return false;
        }
    }

    private boolean reserveBytes(AtomicLong counter, int retainedBytes) {
        long current;
        do {
            current = counter.get();
            if (current > MAX_RETAINED_BYTES - retainedBytes) return false;
        } while (!counter.compareAndSet(current, current + retainedBytes));
        return true;
    }

    /** Cancels queued work and interrupts running decoders for the ending server runtime. */
    @Override
    public void close() {
        ThreadPoolExecutor executor;
        synchronized (this) {
            ExecutorState state = activeState;
            activeState = null;
            if (state == null) return;
            executor = state.executor;
        }
        List<Runnable> abandoned = executor.shutdownNow();
        for (Runnable task : abandoned) {
            if (task instanceof RetainedTask retainedTask) retainedTask.cancelQueued();
        }
        if (!abandoned.isEmpty()) {
            LOGGER.debug("Discarded {} queued QuickSkin texture ingress tasks", abandoned.size());
        }
    }

    private static final class ExecutorState {
        private final ThreadPoolExecutor executor;
        private final AtomicLong retainedBytes = new AtomicLong();

        private ExecutorState(ThreadPoolExecutor executor) {
            this.executor = executor;
        }
    }

    private static final class RetainedTask implements Runnable {
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final AtomicLong retainedCounter;
        private final int retainedBytes;
        private Runnable task;
        private Runnable onAbandoned;

        private RetainedTask(
                Runnable task,
                Runnable onAbandoned,
                AtomicLong retainedCounter,
                int retainedBytes
        ) {
            this.task = task;
            this.onAbandoned = onAbandoned;
            this.retainedCounter = retainedCounter;
            this.retainedBytes = retainedBytes;
        }

        @Override
        public void run() {
            if (!claimed.compareAndSet(false, true)) return;
            Runnable work = task;
            task = null;
            onAbandoned = null;
            try {
                work.run();
            } catch (RuntimeException | LinkageError error) {
                LOGGER.warn("QuickSkin rejected a texture after an ingress worker failure", error);
            } finally {
                retainedCounter.addAndGet(-retainedBytes);
            }
        }

        private void cancelQueued() {
            if (!claimed.compareAndSet(false, true)) return;
            Runnable abandoned = onAbandoned;
            task = null;
            onAbandoned = null;
            try {
                abandoned.run();
            } catch (RuntimeException | LinkageError error) {
                LOGGER.warn("Failed to release abandoned QuickSkin ingress state", error);
            } finally {
                retainedCounter.addAndGet(-retainedBytes);
            }
        }

        private void rejectBeforeEnqueue() {
            if (!claimed.compareAndSet(false, true)) return;
            task = null;
            onAbandoned = null;
            retainedCounter.addAndGet(-retainedBytes);
        }
    }
}
