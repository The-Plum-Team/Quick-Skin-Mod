package com.quickskin.mod.client.services;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.stream.Stream;

/**
 * Throttled change detection for the local upload folders, so a skin or cape copied in from
 * outside the game becomes visible without restarting the client.
 *
 * <p>{@link LocalAssetManager#discoverLocalAssets()} re-reads, re-hashes and re-decodes every
 * candidate file and may rewrite oversized ones, so it must never run per frame. This class holds
 * the cheap half of the poll: a wall-clock throttle plus a metadata-only fingerprint that walks the
 * same folders but only reads each entry's size and modification time. Opening the menu on an
 * unchanged folder therefore costs one directory walk, not a catalog rebuild.
 *
 * <p>The fingerprint is deliberately free of Minecraft state so it can run on
 * {@link com.quickskin.mod.client.concurrent.ClientIoExecutor} without taking the
 * {@code LocalAssetManager} monitor that the render thread needs for
 * {@code getTextureLocation(..)}.
 */
public final class LocalAssetFolderWatch {

    /** Wall-clock gap between two folder polls while a screen is open. */
    public static final long POLL_INTERVAL_MILLIS = 2_000L;

    // Mirror the scanner's own caps so the fingerprint covers exactly what a rescan would see.
    private static final int MAX_WALK_DEPTH = 32;
    private static final int MAX_WALK_ENTRIES = 4096;

    private long nextPollAtMillis = Long.MIN_VALUE;
    private boolean pollInFlight;

    /**
     * Claim the right to start one poll.
     *
     * <p>The first call always succeeds, which is what gives a freshly opened screen its
     * on-open check. Every later call is gated by {@link #POLL_INTERVAL_MILLIS} and by whether the
     * previous poll has reported back, so a slow disk cannot queue overlapping walks.
     *
     * @return {@code true} when the caller owns a poll and must pair it with {@link #finishPoll()}
     */
    public boolean beginPoll(long nowMillis) {
        if (pollInFlight || nowMillis < nextPollAtMillis) {
            return false;
        }
        pollInFlight = true;
        nextPollAtMillis = nowMillis + POLL_INTERVAL_MILLIS;
        return true;
    }

    /** Release the in-flight claim taken by {@link #beginPoll(long)}. */
    public void finishPoll() {
        pollInFlight = false;
    }

    /** Visible for tests: whether a poll claimed by {@link #beginPoll(long)} is still open. */
    boolean isPollInFlight() {
        return pollInFlight;
    }

    /**
     * Metadata-only fingerprint of every regular file below {@code directories}.
     *
     * <p>No file is opened, so the cost is one {@code stat} per entry. A missing or unreadable
     * folder contributes nothing rather than throwing, so a transient I/O failure degrades to
     * "looks unchanged" instead of forcing a rebuild loop.
     *
     * @return a value that changes when a file is added, removed, resized or rewritten
     */
    public static long fingerprint(List<Path> directories) {
        if (directories == null || directories.isEmpty()) {
            return 0L;
        }

        long sum = 0L;
        long mixed = 0L;
        long count = 0L;

        for (Path directory : directories) {
            if (directory == null || !Files.isDirectory(directory)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(directory, MAX_WALK_DEPTH)) {
                for (Path path : paths.limit(MAX_WALK_ENTRIES).filter(Files::isRegularFile).toList()) {
                    long entry = entryFingerprint(directory, path);
                    sum += entry;
                    mixed ^= Long.rotateLeft(entry, (int) (count & 63L));
                    count++;
                }
            } catch (IOException | UncheckedIOException | SecurityException ignored) {
                // An unreadable folder must not turn into a permanent rescan trigger.
            }
        }

        if (count == 0L) {
            return 0L;
        }
        return (sum * 1_000_003L) ^ Long.rotateLeft(mixed, 21) ^ (count * -7046029254386353131L);
    }

    private static long entryFingerprint(Path directory, Path path) {
        long size;
        long lastModifiedMillis;
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            size = attributes.size();
            lastModifiedMillis = attributes.lastModifiedTime().toMillis();
        } catch (IOException | SecurityException ignored) {
            // The entry vanished between the walk and the stat; the next poll will settle it.
            return 0L;
        }

        long nameHash = directory.relativize(path).toString().hashCode() & 0xFFFFFFFFL;
        return nameHash * 31L + size * 17L + lastModifiedMillis;
    }
}
