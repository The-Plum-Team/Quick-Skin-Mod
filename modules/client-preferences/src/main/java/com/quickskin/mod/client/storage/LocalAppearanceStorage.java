package com.quickskin.mod.client.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.common.data.ContentId;
import com.quickskin.mod.common.util.BoundedFileReader;
import com.quickskin.mod.networking.NetworkSecurity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Client-side storage for player appearance preferences
 * Saves local preferences like favorite skins, last used appearance, etc.
 */
@Environment(EnvType.CLIENT)
public class LocalAppearanceStorage {
    private static final int MAX_PREFERENCES_BYTES = 4 * 1024 * 1024;
    private static final int MAX_PLAYERS = 1024;
    private static final int MAX_FAVORITES_PER_PLAYER = 4096;
    private static volatile LocalAppearanceStorage instance;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private Path storageFile;

    private LocalAppearanceStorage() {}

    public static synchronized LocalAppearanceStorage getInstance() {
        if (instance == null) {
            instance = new LocalAppearanceStorage();
        }
        return instance;
    }

    /**
     * Initialize storage with config directory
     */
    public synchronized void init(Path configDirectory) {
        storageFile = Objects.requireNonNull(configDirectory, "configDirectory")
                .resolve("quickskin_preferences.json").toAbsolutePath().normalize();
    }

    /**
     * Player preferences data structure
     */
    public static class PlayerPreferences {
        public String lastSkinId;
        public String lastModelType;
        // Opaque legacy extension data. No current feature reads this map, so its key/value
        // semantics are deliberately preserved instead of guessing which side is a content ID.
        public Map<String, String> favorites;

        public PlayerPreferences() {
            this.favorites = new HashMap<>();
        }
    }

    /**
     * All preferences (keyed by player UUID)
     */
    public static class PreferencesData {
        public Map<String, PlayerPreferences> players;

        public PreferencesData() {
            this.players = new HashMap<>();
        }
    }

    /**
     * Save player preferences
     * @param playerId The player's UUID
     */
    public synchronized void savePlayerPreferences(UUID playerId, String skinId, String modelType) {
        if (storageFile == null || playerId == null) {
            return;
        }

        // Load existing preferences
        PreferencesData data = loadPreferencesData();

        // Get or create preferences for this player
        PlayerPreferences prefs = data.players.computeIfAbsent(
            playerId.toString(),
            k -> new PlayerPreferences()
        );

        // The lifecycle owner supplies the current selection; storage never queries the catalog.
        prefs.lastSkinId = skinId;
        prefs.lastModelType = modelType;

        // Save to disk
        savePreferencesData(data);
    }

    /**
     * Migrates persisted last-used local skin IDs after the asset catalog authenticates aliases.
     * Opaque favorites are retained byte-semantically until a versioned schema defines them.
     */
    public synchronized boolean migrateContentIds(Map<String, String> aliases) {
        if (storageFile == null || aliases == null || aliases.isEmpty()
                || !Files.isRegularFile(storageFile)) {
            return false;
        }
        PreferencesData data = loadPreferencesData();
        boolean changed = false;
        for (PlayerPreferences preferences : data.players.values()) {
            String migrated = aliases.get(preferences.lastSkinId);
            if (isStrongMigration(preferences.lastSkinId, migrated)) {
                preferences.lastSkinId = migrated;
                changed = true;
            }
        }
        return changed && savePreferencesData(data);
    }

    /**
     * Load all preferences from disk
     */
    private PreferencesData loadPreferencesData() {
        if (storageFile == null || !Files.isRegularFile(storageFile)) {
            return new PreferencesData();
        }

        try {
            long size = Files.size(storageFile);
            if (size <= 0 || size > MAX_PREFERENCES_BYTES) {
                QuickSkinInfo.LOGGER.warn("Ignoring oversized local appearance preferences {}", storageFile);
                return new PreferencesData();
            }
            String json = BoundedFileReader.readUtf8(storageFile, MAX_PREFERENCES_BYTES);
            PreferencesData data = GSON.fromJson(json, PreferencesData.class);
            if (data == null) {
                return new PreferencesData();
            }
            normalize(data);
            return data;
        } catch (IOException | RuntimeException e) {
            QuickSkinInfo.LOGGER.warn("Unable to load local appearance preferences {}", storageFile, e);
            return new PreferencesData();
        }
    }

    /**
     * Save all preferences to disk
     */
    private boolean savePreferencesData(PreferencesData data) {
        if (storageFile == null) {
            return false;
        }

        Path temporary = null;
        try {
            normalize(data);
            Files.createDirectories(storageFile.getParent());
            String json = GSON.toJson(data);
            byte[] encoded = json.getBytes(StandardCharsets.UTF_8);
            if (encoded.length > MAX_PREFERENCES_BYTES) {
                QuickSkinInfo.LOGGER.error("Refusing to save oversized local appearance preferences {}",
                        storageFile);
                return false;
            }
            temporary = Files.createTempFile(
                    storageFile.getParent(), ".quickskin-preferences-", ".tmp");
            Files.write(temporary, encoded);
            byte[] verified = BoundedFileReader.readBytes(temporary, MAX_PREFERENCES_BYTES);
            if (!Arrays.equals(encoded, verified)) {
                throw new IOException("Local appearance temp-file verification failed");
            }
            atomicReplace(temporary, storageFile);
            return true;
        } catch (IOException e) {
            QuickSkinInfo.LOGGER.error("Unable to save local appearance preferences {}", storageFile, e);
            return false;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupError) {
                    QuickSkinInfo.LOGGER.debug("Unable to remove local appearance temp file {}",
                            temporary, cleanupError);
                }
            }
        }
    }

    private static void normalize(PreferencesData data) {
        if (data.players == null) {
            data.players = new HashMap<>();
            return;
        }
        data.players.entrySet().removeIf(entry -> entry.getKey() == null
                || entry.getValue() == null || !isUuid(entry.getKey()));
        trimToSize(data.players, MAX_PLAYERS);
        for (PlayerPreferences preferences : data.players.values()) {
            if (preferences.favorites == null) preferences.favorites = new HashMap<>();
            preferences.favorites.entrySet().removeIf(entry -> entry.getKey() == null
                    || entry.getValue() == null || entry.getKey().length() > 256
                    || entry.getValue().length() > 256);
            trimToSize(preferences.favorites, MAX_FAVORITES_PER_PLAYER);
            if (preferences.lastSkinId == null
                    || (!preferences.lastSkinId.isEmpty()
                    && !NetworkSecurity.isValidContentId(preferences.lastSkinId))) {
                preferences.lastSkinId = "";
            }
            if (!NetworkSecurity.isValidModel(preferences.lastModelType)) {
                preferences.lastModelType = "auto";
            }
        }
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isStrongMigration(String legacyValue, String strongValue) {
        ContentId legacy = ContentId.parse(legacyValue);
        ContentId strong = ContentId.parse(strongValue);
        return legacy != null && legacy.algorithm() == ContentId.Algorithm.SHA1
                && strong != null && strong.algorithm() == ContentId.Algorithm.SHA256;
    }

    private static void trimToSize(Map<?, ?> map, int maximumSize) {
        var iterator = map.keySet().iterator();
        while (map.size() > maximumSize && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
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
