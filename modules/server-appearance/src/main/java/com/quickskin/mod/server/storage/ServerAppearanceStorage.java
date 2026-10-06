package com.quickskin.mod.server.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.common.util.BoundedFileReader;
import com.quickskin.mod.networking.NetworkSecurity;
import com.quickskin.mod.server.concurrent.ServerCacheIoExecutor;
import com.quickskin.mod.server.data.ServerPlayerAppearanceRepository;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-side storage for player appearance persistence
 * Saves and loads player appearances to/from disk.
 *
 * <p>A committed appearance change is saved at once through the bounded cache-I/O worker, so a
 * crash does not lose it; disconnect and shutdown still save synchronously. Writes happen under
 * this object's lock, which orders a queued save against a later synchronous one.</p>
 */
public class ServerAppearanceStorage {
    private static final int MAX_APPEARANCE_FILE_BYTES = 64 * 1024;
    private static ServerAppearanceStorage instance;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private Path storageDirectory;
    /** Latest encoded appearance per player awaiting the worker; bounded by the players online. */
    private final Map<UUID, byte[]> pendingSaves = new HashMap<>();

    private ServerAppearanceStorage() {}

    public static ServerAppearanceStorage getInstance() {
        if (instance == null) {
            instance = new ServerAppearanceStorage();
        }
        return instance;
    }

    /**
     * Initialize appearance storage for the world selected by the runtime owner
     */
    public synchronized void init(Path worldPath) {
        // Get server world directory
        storageDirectory = worldPath.resolve("quickskin").resolve("appearances")
                .toAbsolutePath().normalize();

        try {
            Files.createDirectories(storageDirectory);
        } catch (IOException e) {
            QuickSkinInfo.LOGGER.error("Unable to initialize player appearance storage {}", storageDirectory, e);
            storageDirectory = null;
        }
    }

    /** Releases the world-specific path so a later integrated server cannot reuse it. */
    public synchronized void clear() {
        storageDirectory = null;
        pendingSaves.clear();
    }

    /**
     * Load a player's saved appearance from disk
     * @param playerId The player's UUID
     * @return The loaded appearance, or null if not found
     */
    @Nullable
    public synchronized PlayerAppearance loadPlayerAppearance(UUID playerId) {
        if (storageDirectory == null || playerId == null) {
            return null;
        }

        Path file = storageDirectory.resolve(playerId.toString() + ".json");

        if (!Files.isRegularFile(file)) {
            return null;
        }

        try {
            if (Files.size(file) <= 0 || Files.size(file) > MAX_APPEARANCE_FILE_BYTES) {
                QuickSkinInfo.LOGGER.warn("Ignoring oversized player appearance file {}", file);
                return null;
            }
            String json = BoundedFileReader.readUtf8(file, MAX_APPEARANCE_FILE_BYTES);
            PlayerAppearance appearance = GSON.fromJson(json, PlayerAppearance.class);
            if (appearance == null || !playerId.equals(appearance.getPlayerId())
                    || !NetworkSecurity.isValidLocalAppearanceId(appearance.getSkinId(), "skin")
                    || !NetworkSecurity.isValidLocalAppearanceId(appearance.getCapeId(), "cape")
                    || !NetworkSecurity.isValidModel(appearance.getModel())) {
                QuickSkinInfo.LOGGER.warn("Ignoring invalid player appearance file {}", file);
                return null;
            }

            // Update the repository with loaded data
            PlayerAppearance restored = restore(appearance);
            if (restored == null) {
                QuickSkinInfo.LOGGER.warn(
                        "Refusing saved appearance for {} because its active texture pins are unavailable or over budget",
                        playerId);
                return null;
            }

            return restored;
        } catch (IOException | RuntimeException e) {
            QuickSkinInfo.LOGGER.warn("Unable to load player appearance {}", file, e);
            return null;
        }
    }

    /**
     * Applies a saved appearance. When its pins are refused and one of its textures is no longer
     * held by the texture cache (stored above a lowered {@code maxTextureUploadKilobytes}, which
     * the cache does not load, or no longer stored at all), the rest is kept: that texture's id
     * is cleared, exactly as if the client had sent it empty, and the other texture and the model
     * are applied when their own pins are accepted. A texture that is held but owned by someone
     * else, or pins over the budget, still refuse the whole appearance.
     */
    @Nullable
    private static PlayerAppearance restore(PlayerAppearance saved) {
        ServerPlayerAppearanceRepository repository = ServerPlayerAppearanceRepository.getInstance();
        if (repository.trySetAppearance(saved)) return saved;
        String skinId = heldTextureOrEmpty(saved.getSkinId(), "local_skin:");
        String capeId = heldTextureOrEmpty(saved.getCapeId(), "local_cape:");
        boolean skinKept = Objects.equals(skinId, saved.getSkinId());
        boolean capeKept = Objects.equals(capeId, saved.getCapeId());
        if (skinKept && capeKept) return null;
        PlayerAppearance partial = new PlayerAppearance(
                saved.getPlayerId(), skinId, capeId, saved.getModel());
        if (!repository.trySetAppearance(partial)) return null;
        QuickSkinInfo.LOGGER.info("Restored the saved appearance of {} without its {}: this server"
                        + " no longer holds that texture (over maxTextureUploadKilobytes or removed)",
                saved.getPlayerId(), skinKept ? "cape" : capeKept ? "skin" : "skin and cape");
        return partial;
    }

    /** The id unchanged, or empty when it names a local texture the cache does not hold. */
    private static String heldTextureOrEmpty(String appearanceId, String prefix) {
        if (appearanceId == null || !appearanceId.startsWith(prefix)) return appearanceId;
        String contentId = appearanceId.substring(prefix.length());
        return ServerTextureCache.getInstance().resolveContentId(contentId) == null
                ? "" : appearanceId;
    }

    /**
     * Save a player's appearance to disk
     * @param playerId The player's UUID
     */
    public synchronized void savePlayerAppearance(UUID playerId) {
        if (storageDirectory == null || playerId == null) {
            return;
        }
        // This save is newer than any queued one, which must not overwrite it afterwards.
        pendingSaves.remove(playerId);
        byte[] encoded = encodeAppearance(playerId);
        if (encoded != null) {
            writeAppearance(playerId, encoded);
        }
    }

    /**
     * Saves a committed appearance change without waiting for the player to leave. The JSON is
     * captured now; the file is written on the cache-I/O worker, where a later change of the same
     * player replaces a save that is still queued.
     */
    public synchronized void scheduleSavePlayerAppearance(UUID playerId) {
        if (storageDirectory == null || playerId == null) {
            return;
        }
        byte[] encoded = encodeAppearance(playerId);
        if (encoded == null) {
            return;
        }
        if (pendingSaves.put(playerId, encoded) != null) {
            return;
        }
        Path directory = storageDirectory;
        if (!ServerCacheIoExecutor.getInstance().submit(() -> writePendingSave(directory, playerId))) {
            pendingSaves.remove(playerId);
            QuickSkinInfo.LOGGER.warn(
                    "Deferred saving the appearance of {} until they leave: the save queue is unavailable",
                    playerId);
        }
    }

    private synchronized void writePendingSave(Path directory, UUID playerId) {
        byte[] encoded = pendingSaves.remove(playerId);
        if (encoded != null && directory.equals(storageDirectory)) {
            writeAppearance(playerId, encoded);
        }
    }

    @Nullable
    private byte[] encodeAppearance(UUID playerId) {
        PlayerAppearance appearance = ServerPlayerAppearanceRepository.getInstance().getAppearance(playerId);

        if (appearance == null) {
            return null;
        }
        byte[] encoded = GSON.toJson(appearance).getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_APPEARANCE_FILE_BYTES) {
            QuickSkinInfo.LOGGER.error("Refusing to save oversized player appearance for {}", playerId);
            return null;
        }
        return encoded;
    }

    private void writeAppearance(UUID playerId, byte[] encoded) {
        Path file = storageDirectory.resolve(playerId.toString() + ".json");

        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.write(temporary, encoded);
            atomicReplace(temporary, file);
        } catch (IOException e) {
            QuickSkinInfo.LOGGER.error("Unable to save player appearance {}", file, e);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupError) {
                QuickSkinInfo.LOGGER.debug("Unable to remove player appearance temp file {}",
                        temporary, cleanupError);
            }
        }
    }

    private static void atomicReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

}
