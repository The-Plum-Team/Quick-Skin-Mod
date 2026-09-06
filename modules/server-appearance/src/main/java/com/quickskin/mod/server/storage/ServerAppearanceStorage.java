package com.quickskin.mod.server.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.common.util.BoundedFileReader;
import com.quickskin.mod.networking.NetworkSecurity;
import com.quickskin.mod.server.data.ServerPlayerAppearanceRepository;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Server-side storage for player appearance persistence
 * Saves and loads player appearances to/from disk
 */
public class ServerAppearanceStorage {
    private static final int MAX_APPEARANCE_FILE_BYTES = 64 * 1024;
    private static ServerAppearanceStorage instance;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private Path storageDirectory;

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
            if (!ServerPlayerAppearanceRepository.getInstance().trySetAppearance(appearance)) {
                QuickSkinInfo.LOGGER.warn(
                        "Refusing saved appearance for {} because its active texture pins are unavailable or over budget",
                        playerId);
                return null;
            }

            return appearance;
        } catch (IOException | RuntimeException e) {
            QuickSkinInfo.LOGGER.warn("Unable to load player appearance {}", file, e);
            return null;
        }
    }

    /**
     * Save a player's appearance to disk
     * @param playerId The player's UUID
     */
    public synchronized void savePlayerAppearance(UUID playerId) {
        if (storageDirectory == null || playerId == null) {
            return;
        }

        PlayerAppearance appearance = ServerPlayerAppearanceRepository.getInstance().getAppearance(playerId);

        if (appearance == null) {
            return;
        }

        Path file = storageDirectory.resolve(playerId.toString() + ".json");

        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            String json = GSON.toJson(appearance);
            byte[] encoded = json.getBytes(StandardCharsets.UTF_8);
            if (encoded.length > MAX_APPEARANCE_FILE_BYTES) {
                QuickSkinInfo.LOGGER.error("Refusing to save oversized player appearance {}", file);
                return;
            }
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
