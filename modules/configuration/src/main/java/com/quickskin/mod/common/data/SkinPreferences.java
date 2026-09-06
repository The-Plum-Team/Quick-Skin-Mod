package com.quickskin.mod.common.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.common.util.BoundedFileReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Stores per-skin user preferences (model type, etc.)
 * Persisted to JSON file in config directory
 */
public class SkinPreferences {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final int MAX_PREFERENCES = 4096;

    // Map of skin hash -> preference data
    private Map<String, SkinPreference> preferences = new HashMap<>();
    // Runtime-only bridge while a verified on-disk alias migration is waiting to be retried.
    private transient Map<String, String> strongToLegacyFallbacks = new HashMap<>();

    /**
     * Individual skin preference
     */
    public static class SkinPreference {
        public String modelType = "auto"; // "auto", "classic", or "slim"

        public SkinPreference() {
            // Default constructor for Gson
        }

    }

    /**
     * Get model type preference for a skin
     * @param hash Skin hash
     * @return Model type ("auto", "classic", or "slim"), defaults to "auto"
     */
    public synchronized String getModelType(String hash) {
        SkinPreference pref = preferences.get(hash);
        if (pref == null) {
            String legacy = aliasFallbacks().get(hash);
            if (legacy != null) pref = preferences.get(legacy);
        }
        return pref != null && isModel(pref.modelType) ? pref.modelType : "auto";
    }

    /**
     * Set model type preference for a skin
     * @param hash Skin hash
     * @param modelType Model type ("auto", "classic", or "slim")
     */
    public synchronized void setModelType(String hash, String modelType) {
        if (!isHash(hash) || !isModel(modelType)
                || (!preferences.containsKey(hash) && preferences.size() >= MAX_PREFERENCES)) {
            return;
        }
        preferences.computeIfAbsent(hash, k -> new SkinPreference()).modelType = modelType;
    }

    /**
     * Remove preferences for a skin (e.g., when skin is deleted)
     * @param hash Skin hash
     */
    public synchronized void remove(String hash) {
        preferences.remove(hash);
        String legacy = aliasFallbacks().remove(hash);
        if (legacy != null) preferences.remove(legacy);
        aliasFallbacks().entrySet().removeIf(entry -> hash != null
                && hash.equals(entry.getValue()));
    }

    /**
     * Clear all preferences
     */
    public synchronized void clear() {
        preferences.clear();
        aliasFallbacks().clear();
    }

    /**
     * Load preferences from file
     * @param path Path to JSON file
     * @return Loaded preferences, or new instance if file doesn't exist
     */
    public static SkinPreferences load(Path path) {
        if (!Files.exists(path)) {
            return new SkinPreferences();
        }

        try {
            String json = BoundedFileReader.readUtf8(path, MAX_FILE_BYTES);
            SkinPreferences prefs = GSON.fromJson(json, SkinPreferences.class);
            if (prefs == null) return new SkinPreferences();
            prefs.normalize();
            return prefs;
        } catch (IOException | RuntimeException e) {
            QuickSkinInfo.LOGGER.warn("Unable to load skin preferences {}", path, e);
            return new SkinPreferences();
        }
    }

    /**
     * Save preferences to file
     * @param path Path to JSON file
     */
    public synchronized void save(Path path) {
        try {
            normalize();
            writeVerified(path, preferences);
        } catch (IOException e) {
            QuickSkinInfo.LOGGER.error("Unable to save skin preferences {}", path, e);
        }
    }

    /**
     * Moves preferences from unambiguous SHA-1 aliases to SHA-256 primaries. The replacement is
     * fully written and byte-verified before the old on-disk document is replaced; on failure the
     * in-memory map and the legacy file are both retained.
     */
    public synchronized boolean migrateAliases(Map<String, String> aliases, Path path) {
        if (aliases == null || aliases.isEmpty() || path == null) return false;
        normalize();
        Map<String, SkinPreference> migrated = new HashMap<>(preferences);
        boolean changed = false;
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            ContentId legacy = ContentId.parse(alias.getKey());
            ContentId strong = ContentId.parse(alias.getValue());
            if (legacy == null || legacy.algorithm() != ContentId.Algorithm.SHA1
                    || strong == null || strong.algorithm() != ContentId.Algorithm.SHA256) {
                continue;
            }
            SkinPreference oldPreference = migrated.get(alias.getKey());
            if (oldPreference == null) continue;
            migrated.putIfAbsent(alias.getValue(), oldPreference);
            migrated.remove(alias.getKey());
            changed = true;
        }
        if (!changed) return false;

        try {
            writeVerified(path, migrated);
            preferences = migrated;
            for (String strong : aliases.values()) aliasFallbacks().remove(strong);
            return true;
        } catch (IOException error) {
            // Keep the legacy map retryable without making canonical runtime lookups lose the
            // user's preference. This bridge is transient and never weakens the persisted file.
            for (Map.Entry<String, String> alias : aliases.entrySet()) {
                if (preferences.containsKey(alias.getKey())
                        && !preferences.containsKey(alias.getValue())) {
                    aliasFallbacks().put(alias.getValue(), alias.getKey());
                }
            }
            QuickSkinInfo.LOGGER.warn("Unable to migrate skin preferences {}", path, error);
            return false;
        }
    }

    /**
     * Get number of stored preferences
     */
    public synchronized int size() {
        return preferences.size();
    }

    private void normalize() {
        if (preferences == null) preferences = new HashMap<>();
        preferences.entrySet().removeIf(entry -> !isHash(entry.getKey())
                || entry.getValue() == null || !isModel(entry.getValue().modelType));
        var iterator = preferences.keySet().iterator();
        while (preferences.size() > MAX_PREFERENCES && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private Map<String, String> aliasFallbacks() {
        if (strongToLegacyFallbacks == null) strongToLegacyFallbacks = new HashMap<>();
        return strongToLegacyFallbacks;
    }

    private static boolean isHash(String hash) {
        return ContentId.parse(hash) != null;
    }

    private static boolean isModel(String model) {
        return "auto".equals(model) || "classic".equals(model) || "slim".equals(model);
    }

    private static void atomicReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeVerified(
            Path path, Map<String, SkinPreference> preferenceSnapshot) throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IOException("Skin preferences have no parent directory");
        Files.createDirectories(parent);

        SkinPreferences document = new SkinPreferences();
        document.preferences = new HashMap<>(preferenceSnapshot);
        document.normalize();
        byte[] encoded = GSON.toJson(document).getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_FILE_BYTES) {
            throw new IOException("Skin preferences exceed the size limit");
        }

        Path temporary = Files.createTempFile(parent, ".skin-preferences-", ".tmp");
        try {
            Files.write(temporary, encoded);
            byte[] verified = BoundedFileReader.readBytes(temporary, MAX_FILE_BYTES);
            if (!Arrays.equals(encoded, verified)) {
                throw new IOException("Skin preferences temp-file verification failed");
            }
            atomicReplace(temporary, path.toAbsolutePath().normalize());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
