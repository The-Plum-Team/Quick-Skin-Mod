package com.quickskin.mod.client.gui.util;

import com.quickskin.mod.client.concurrent.ClientIoExecutor;
import com.quickskin.mod.common.util.GifDecoder;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Sequential state machine for a bounded batch of cape imports. */
@Environment(EnvType.CLIENT)
public final class CapeImportWorkflow {
    private static final int MAX_BATCH_FILES = 16;
    private static final long MAX_BATCH_SOURCE_BYTES = 64L * 1024L * 1024L;

    private final ArrayDeque<Path> pending = new ArrayDeque<>();
    private final Path targetDirectory;
    private final Path metadataDirectory;
    private final BufferedImage vanillaElytra;
    private final GifDecoder gifDecoder;
    private final Executor mainExecutor;
    private final AdjustmentHandler adjustmentHandler;
    private final Consumer<Summary> completion;

    private int succeeded;
    private int failed;
    private int cancelledCount;
    private String firstError;
    private boolean cancelled;
    private boolean finished;

    public record Summary(int succeeded, int failed, int cancelled, String firstError) {
    }

    @FunctionalInterface
    public interface AdjustmentHandler {
        void request(
                CapeImportProcessor.PreparedCape prepared,
                Consumer<BufferedImage> apply,
                Runnable cancel
        );
    }

    private record PreparedResult(CapeImportProcessor.PreparedCape prepared, String error) {
        private static PreparedResult saved() {
            return new PreparedResult(null, null);
        }

        private static PreparedResult adjustment(CapeImportProcessor.PreparedCape prepared) {
            return new PreparedResult(prepared, null);
        }

        private static PreparedResult failed(String error) {
            return new PreparedResult(null, error);
        }
    }

    public CapeImportWorkflow(
            List<Path> sources,
            Path targetDirectory,
            Path metadataDirectory,
            BufferedImage vanillaElytra,
            GifDecoder gifDecoder,
            Executor mainExecutor,
            AdjustmentHandler adjustmentHandler,
            Consumer<Summary> completion
    ) {
        this.targetDirectory = targetDirectory;
        this.metadataDirectory = metadataDirectory;
        this.vanillaElytra = vanillaElytra;
        this.gifDecoder = java.util.Objects.requireNonNull(gifDecoder, "gifDecoder");
        this.mainExecutor = mainExecutor;
        this.adjustmentHandler = adjustmentHandler;
        this.completion = completion;

        long retainedSourceBytes = 0L;
        if (sources != null) {
            for (Path source : sources) {
                if (pending.size() >= MAX_BATCH_FILES) {
                    reject("Cape import batches are limited to 16 files");
                    continue;
                }
                try {
                    long bytes = Files.size(source);
                    if (bytes <= 0 || bytes > 32L * 1024L * 1024L
                            || retainedSourceBytes + bytes > MAX_BATCH_SOURCE_BYTES) {
                        reject("Cape import batch exceeds its 64 MB source limit");
                        continue;
                    }
                    pending.addLast(source);
                    retainedSourceBytes += bytes;
                } catch (IOException | RuntimeException error) {
                    reject(error.getMessage());
                }
            }
        }
    }

    public void start() {
        mainExecutor.execute(this::processNext);
    }

    public synchronized boolean isFinished() {
        return finished;
    }

    public synchronized void cancel() {
        if (finished) return;
        cancelled = true;
        cancelledCount += pending.size();
        pending.clear();
    }

    private void processNext() {
        Path source;
        synchronized (this) {
            if (cancelled || finished) {
                finish();
                return;
            }
            source = pending.pollFirst();
            if (source == null) {
                finish();
                return;
            }
        }

        ClientIoExecutor.supplyAsync(() -> prepareOrSave(source))
                .whenComplete((result, throwable) -> mainExecutor.execute(() -> {
                    if (throwable != null) {
                        recordFailure(messageOf(throwable));
                        processNext();
                        return;
                    }
                    handlePrepared(result);
                }));
    }

    private PreparedResult prepareOrSave(Path source) {
        try {
            CapeImportProcessor.PreparedCape prepared = CapeImportProcessor.prepare(source, gifDecoder);
            if (!prepared.standardFormat()) {
                return PreparedResult.adjustment(prepared);
            }
            CapeImportProcessor.saveStandard(
                    prepared, targetDirectory, metadataDirectory, vanillaElytra);
            return PreparedResult.saved();
        } catch (IOException | RuntimeException error) {
            return PreparedResult.failed(error.getMessage());
        }
    }

    private void handlePrepared(PreparedResult result) {
        synchronized (this) {
            if (finished) {
                return;
            }
        }
        if (result.error() != null) {
            recordFailure(result.error());
            continueAfterSettledImport();
            return;
        }
        if (result.prepared() == null) {
            recordSuccess();
            continueAfterSettledImport();
            return;
        }

        synchronized (this) {
            if (cancelled) {
                cancelledCount++;
                finish();
                return;
            }
        }

        AtomicBoolean answered = new AtomicBoolean();
        try {
            adjustmentHandler.request(
                    result.prepared(),
                    adjusted -> {
                        if (!answered.compareAndSet(false, true)) return;
                        saveAdjusted(result.prepared(), adjusted);
                    },
                    () -> {
                        if (!answered.compareAndSet(false, true)) return;
                        recordCancellation();
                        continueAfterSettledImport();
                    });
        } catch (RuntimeException error) {
            if (answered.compareAndSet(false, true)) {
                recordFailure(error.getMessage());
                continueAfterSettledImport();
            }
        }
    }

    private void saveAdjusted(
            CapeImportProcessor.PreparedCape prepared, BufferedImage adjusted) {
        ClientIoExecutor.supplyAsync(() -> {
            try {
                CapeImportProcessor.saveAdjusted(
                        prepared, adjusted, targetDirectory, metadataDirectory, vanillaElytra);
                return (String) null;
            } catch (IOException | RuntimeException error) {
                return error.getMessage();
            }
        }).whenComplete((error, throwable) -> mainExecutor.execute(() -> {
            if (throwable != null) {
                recordFailure(messageOf(throwable));
            } else if (error != null) {
                recordFailure(error);
            } else {
                recordSuccess();
            }
            continueAfterSettledImport();
        }));
    }

    private void continueAfterSettledImport() {
        synchronized (this) {
            if (cancelled || finished) {
                finish();
                return;
            }
        }
        processNext();
    }

    private synchronized void recordSuccess() {
        succeeded++;
    }

    private synchronized void recordCancellation() {
        cancelledCount++;
    }

    private synchronized void reject(String error) {
        failed++;
        if (firstError == null && error != null && !error.isBlank()) firstError = error;
    }

    private synchronized void recordFailure(String error) {
        reject(error != null ? error : "Cape import failed");
    }

    private synchronized void finish() {
        if (finished) return;
        finished = true;
        completion.accept(new Summary(succeeded, failed, cancelledCount, firstError));
    }

    private static String messageOf(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause != cause.getCause()) cause = cause.getCause();
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }
}
