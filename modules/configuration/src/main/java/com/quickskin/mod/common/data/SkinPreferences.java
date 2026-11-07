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
    // Legacy entries a migration kept because their strong entry differs (strong -> legacy), so
    // deleting the skin also drops them. Runtime only: each start's migration finds them again.
    private transient Map<String, String> keptLegacyEntries = new HashMap<>();
    // False when the file exists but could not be read: it is then never saved over.
    private transient boolean persistable = true;

    /**
     * Individual skin preference
     */
    public static class SkinPreference {
        public String modelType = "auto"; // "auto", "classic", or "slim"
        public boolean favorite; // listed first, after the player's own skin, and drawn in gold
        // The cape wearing this skin puts on ("local_cape:<id>" or "known:<id>"), or null for none
        public String cape;
        // What the elytra wear with this skin: "" the cape, "minecraft:elytra" vanilla's, a cape
        // ID, or null to keep what they wear
        public String elytra;

        public SkinPreference() {
            // Default constructor for Gson
        }

        private SkinPreference copy() {
            SkinPreference copy = new SkinPreference();
            copy.modelType = modelType;
            copy.favorite = favorite;
            copy.cape = cape;
            copy.elytra = elytra;
            return copy;
        }

        private boolean sameAs(SkinPreference other) {
            return other != null && favorite == other.favorite
                    && java.util.Objects.equals(modelType, other.modelType)
                    && java.util.Objects.equals(cape, other.cape)
                    && java.util.Objects.equals(elytra, other.elytra);
        }
    }

    /**
     * Get model type preference for a skin
     * @param hash Skin hash
     * @return Model type ("auto", "classic", or "slim"), defaults to "auto"
     */
    public synchronized String getModelType(String hash) {
        SkinPreference pref = readable(hash);
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
        writable(hash).modelType = modelType;
    }

    /** Whether the player marked this skin as a favorite. */
    public synchronized boolean isFavorite(String hash) {
        SkinPreference pref = readable(hash);
        return pref != null && pref.favorite;
    }

    /** Marks or unmarks a skin as a favorite. */
    public synchronized void setFavorite(String hash, boolean favorite) {
        if (!isHash(hash) || (!preferences.containsKey(hash) && preferences.size() >= MAX_PREFERENCES)) {
            return;
        }
        if (!favorite && readable(hash) == null) {
            return;
        }
        writable(hash).favorite = favorite;
    }

    /** The cape this skin puts on when it is worn, or null when it keeps the cape worn. */
    public synchronized String getCape(String hash) {
        SkinPreference pref = readable(hash);
        return pref != null && isCapeId(pref.cape) ? pref.cape : null;
    }

    /** Gives a skin a cape of its own, or takes it away with null. An invalid cape ID is ignored. */
    public synchronized void setCape(String hash, String capeId) {
        if (!isHash(hash) || (capeId != null && !isCapeId(capeId))
                || (!preferences.containsKey(hash) && preferences.size() >= MAX_PREFERENCES)) {
            return;
        }
        if (capeId == null && readable(hash) == null) {
            return;
        }
        writable(hash).cape = capeId;
    }

    /**
     * What the elytra wear when this skin is worn: "" the cape, {@link #VANILLA_ELYTRA} vanilla's
     * elytra, a cape ID, or null when they keep what they wear.
     */
    public synchronized String getElytra(String hash) {
        SkinPreference pref = readable(hash);
        return pref != null && isElytraId(pref.elytra) ? pref.elytra : null;
    }

    /** Gives a skin elytra of its own (see {@link #getElytra}), or takes them away with null. */
    public synchronized void setElytra(String hash, String elytraId) {
        if (!isHash(hash) || (elytraId != null && !isElytraId(elytraId))
                || (!preferences.containsKey(hash) && preferences.size() >= MAX_PREFERENCES)) {
            return;
        }
        if (elytraId == null && readable(hash) == null) {
            return;
        }
        writable(hash).elytra = elytraId;
    }

    /** The skin's preference, through the legacy alias it is still stored under if need be. */
    private SkinPreference readable(String hash) {
        SkinPreference pref = preferences.get(hash);
        if (pref == null) {
            String legacy = aliasFallbacks().get(hash);
            if (legacy != null) pref = preferences.get(legacy);
        }
        return pref;
    }

    /**
     * The skin's own preference to change, created from the legacy alias's when the migration of
     * that alias has not been written yet, so a change keeps the settings it does not touch.
     */
    private SkinPreference writable(String hash) {
        SkinPreference existing = preferences.get(hash);
        if (existing != null) return existing;
        SkinPreference inherited = readable(hash);
        SkinPreference created = inherited != null ? inherited.copy() : new SkinPreference();
        preferences.put(hash, created);
        return created;
    }

    /**
     * Remove preferences for a skin (e.g., when skin is deleted)
     * @param hash Skin hash
     */
    public synchronized void remove(String hash) {
        preferences.remove(hash);
        String legacy = aliasFallbacks().remove(hash);
        if (legacy != null) preferences.remove(legacy);
        String kept = keptLegacy().remove(hash);
        if (kept != null) preferences.remove(kept);
        aliasFallbacks().entrySet().removeIf(entry -> hash != null
                && hash.equals(entry.getValue()));
    }

    /**
     * Clear all preferences
     */
    public synchronized void clear() {
        preferences.clear();
        aliasFallbacks().clear();
        keptLegacy().clear();
    }

    /**
     * Load preferences from file
     * @param path Path to JSON file
     * @return Loaded preferences, or new instance if the file is missing or empty. A file that
     *     exists but cannot be read gives empty preferences that are never saved over it, so a
     *     broken file cannot cost the player every favourite and model choice.
     */
    public static SkinPreferences load(Path path) {
        if (!Files.exists(path)) {
            return new SkinPreferences();
        }

        try {
            String json = BoundedFileReader.readUtf8(path, MAX_FILE_BYTES);
            if (json.isBlank()) return new SkinPreferences();
            SkinPreferences prefs = GSON.fromJson(json, SkinPreferences.class);
            if (prefs != null) {
                prefs.normalize();
                return prefs;
            }
            QuickSkinInfo.LOGGER.error("Skin preferences {} contained JSON null; leaving the file"
                    + " untouched until it is fixed", path);
        } catch (IOException | RuntimeException e) {
            QuickSkinInfo.LOGGER.error("Unable to load skin preferences {}; leaving the file"
                    + " untouched until it is fixed", path, e);
        }
        SkinPreferences unreadable = new SkinPreferences();
        unreadable.persistable = false;
        return unreadable;
    }

    /** Whether {@link #save} and {@link #migrateAliases} may write the file it was loaded from. */
    public synchronized boolean isPersistable() {
        return persistable;
    }

    /**
     * Save preferences to file
     * @param path Path to JSON file
     */
    public synchronized void save(Path path) {
        if (!persistable) {
            QuickSkinInfo.LOGGER.warn("Not saving skin preferences {}: the file could not be read"
                    + " at start, so it is left as it is", path);
            return;
        }
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
        if (aliases == null || aliases.isEmpty() || path == null || !persistable) return false;
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
            SkinPreference existing = migrated.get(alias.getValue());
            // The legacy entry goes only when the strong one is new, equal, or was created from
            // it after a failed migration (and so holds the player's later choice); a strong
            // entry that differs on its own keeps the legacy one, which is never discarded.
            boolean derived = alias.getKey().equals(aliasFallbacks().get(alias.getValue()));
            if (existing != null && !existing.sameAs(oldPreference) && !derived) {
                keptLegacy().put(alias.getValue(), alias.getKey());
                continue;
            }
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
        // A cape that is not a valid cape ID is dropped alone; the entry's other choices stay
        for (SkinPreference preference : preferences.values()) {
            if (preference.cape != null && !isCapeId(preference.cape)) preference.cape = null;
            if (preference.elytra != null && !isElytraId(preference.elytra)) preference.elytra = null;
        }
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

    private Map<String, String> keptLegacy() {
        if (keptLegacyEntries == null) keptLegacyEntries = new HashMap<>();
        return keptLegacyEntries;
    }

    private static boolean isHash(String hash) {
        return ContentId.parse(hash) != null;
    }

    /**
     * A catalogued cape ({@code local_cape:} and a content ID) or a built-in one ({@code known:}
     * and a short lower-case name), the two kinds of cape the cape menu can pick.
     */
    static boolean isCapeId(String capeId) {
        if (capeId == null || capeId.length() > 128) return false;
        if (capeId.startsWith("local_cape:")) {
            return ContentId.parse(capeId.substring("local_cape:".length())) != null;
        }
        if (capeId.startsWith("known:")) {
            return capeId.substring("known:".length()).matches("[a-z0-9_]{1,64}");
        }
        return false;
    }

    /** The elytra's own vanilla texture, as {@code PlayerAppearance} names it. */
    public static final String VANILLA_ELYTRA = "minecraft:elytra";

    /** What a skin's elytra may wear: the cape (""), vanilla's elytra, or a cape. */
    static boolean isElytraId(String elytraId) {
        return elytraId != null && (elytraId.isEmpty() || VANILLA_ELYTRA.equals(elytraId) || isCapeId(elytraId));
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
