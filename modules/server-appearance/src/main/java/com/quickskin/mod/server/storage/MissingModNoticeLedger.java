package com.quickskin.mod.server.storage;

import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.server.concurrent.ServerCacheIoExecutor;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Which players of this world were already told that the server uses Quick Skin while they joined
 * without it. Each one is an empty file named after the player's UUID in
 * {@code <world>/quickskin/missing_mod_notices/}; deleting a file (or the folder) tells that
 * player (or everyone) again.
 */
public final class MissingModNoticeLedger {
    /** Players recorded this run whose file may still be queued; the file decides beyond it. */
    static final int MAX_REMEMBERED = 4096;
    private static final MissingModNoticeLedger INSTANCE =
            new MissingModNoticeLedger(ServerCacheIoExecutor.getInstance()::submit);

    private final Submitter submitter;
    private final Set<UUID> recordedThisRun = new HashSet<>();
    private Path directory;

    interface Submitter {
        boolean submit(Runnable operation);
    }

    MissingModNoticeLedger(Submitter submitter) {
        this.submitter = submitter;
    }

    public static MissingModNoticeLedger getInstance() {
        return INSTANCE;
    }

    public synchronized void init(Path worldPath) {
        recordedThisRun.clear();
        directory = worldPath.resolve("quickskin").resolve("missing_mod_notices")
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            QuickSkinInfo.LOGGER.error("Unable to open the record of players told about Quick Skin {}"
                    + "; nobody is told this session", directory, e);
            directory = null;
        }
    }

    /** Releases the world-specific path so a later integrated server cannot reuse it. */
    public synchronized void clear() {
        directory = null;
        recordedThisRun.clear();
    }

    /**
     * Whether this player was already told in this world. Without a usable record the answer is
     * yes, so a broken folder never repeats the message at every join.
     */
    public synchronized boolean hasBeenNotified(UUID playerId) {
        if (directory == null || playerId == null) return true;
        if (recordedThisRun.contains(playerId)) return true;
        return Files.exists(directory.resolve(playerId.toString()));
    }

    /** Records that the player was told; the file is written on the cache-I/O worker. */
    public synchronized void recordNotified(UUID playerId) {
        if (directory == null || playerId == null) return;
        if (recordedThisRun.size() < MAX_REMEMBERED) recordedThisRun.add(playerId);
        Path file = directory.resolve(playerId.toString());
        if (!submitter.submit(() -> writeMarker(file))) {
            QuickSkinInfo.LOGGER.warn("Could not record that {} was told about Quick Skin: the save"
                    + " queue is unavailable, so they may be told again", playerId);
        }
    }

    private static void writeMarker(Path file) {
        try {
            Files.createFile(file);
        } catch (FileAlreadyExistsException ignored) {
            // Told before; nothing to record.
        } catch (IOException e) {
            QuickSkinInfo.LOGGER.warn("Unable to record that a player was told about Quick Skin {}", file, e);
        }
    }
}
