package com.quickskin.mod.config;

import com.quickskin.mod.common.data.ContentId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Skin selections of this instance, one per launcher account (profile UUID plus exact name).
 * Several accounts can play from one instance, so a selection never follows the instance alone.
 */
public final class AccountSkinPreferences {
    private static final int MAX_ACCOUNTS = 128;
    private static final int MAX_NAME_LENGTH = 64;
    private static final int UUID_LENGTH = 36;

    private Map<String, String> skins = new LinkedHashMap<>();
    private boolean migrated;

    /** Returns the key of a launcher account, or null when its identity cannot be stored. */
    public static String key(UUID profileId, String name) {
        if (profileId == null || name == null) return null;
        String key = profileId + ":" + name;
        return isKey(key) ? key : null;
    }

    /** The skin this account selected here, or an empty string when it has selected none. */
    public String skinHash(String key) {
        String skinHash = key != null ? skins.get(key) : null;
        return skinHash != null ? skinHash : "";
    }

    public void select(String key, String skinHash) {
        if (!isKey(key) || ContentId.parse(skinHash) == null) return;
        skins.remove(key);
        while (skins.size() >= MAX_ACCOUNTS) {
            skins.remove(skins.keySet().iterator().next());
        }
        skins.put(key, skinHash);
    }

    public void remove(String key) {
        if (key != null) skins.remove(key);
    }

    /**
     * What a join makes of the instance's active skin: the hash that is active from now on,
     * whether the configuration must be saved, and whether the account has a selection (one
     * without waits for the skin the server saved for its player).
     */
    public record Projection(String activeSkinHash, boolean changed, boolean hasSelection) {
    }

    /**
     * Makes the active skin the one the joining account selected in this instance. An account
     * that cannot be stored keeps the instance-wide skin, and so does every account while a CPM
     * model is active: the model is instance-wide and excludes a skin hash. Neither waits.
     */
    public Projection project(String key, String activeSkinHash, boolean cpmModelActive) {
        if (!isKey(key)) return new Projection(activeSkinHash, false, true);
        boolean changed = migrate(key, activeSkinHash);
        if (cpmModelActive) return new Projection(activeSkinHash, changed, true);
        String skinHash = skinHash(key);
        return new Projection(
                skinHash, changed || !skinHash.equals(activeSkinHash), !skinHash.isEmpty());
    }

    /**
     * Hands the selection that predates per-account storage to the first account that joins.
     * Only the first call can do so; it returns true to ask for the result to be saved. Later,
     * a skin left active by another account or restored from a server is never inherited.
     */
    boolean migrate(String key, String legacySkinHash) {
        if (migrated || !isKey(key)) return false;
        migrated = true;
        if (skins.isEmpty()) select(key, legacySkinHash);
        return true;
    }

    void normalize() {
        if (skins == null) skins = new LinkedHashMap<>();
        skins.entrySet().removeIf(entry -> !isKey(entry.getKey())
                || ContentId.parse(entry.getValue()) == null);
        while (skins.size() > MAX_ACCOUNTS) {
            skins.remove(skins.keySet().iterator().next());
        }
    }

    static boolean isKey(String key) {
        if (key == null || key.length() <= UUID_LENGTH + 1
                || key.length() > UUID_LENGTH + 1 + MAX_NAME_LENGTH
                || key.charAt(UUID_LENGTH) != ':'
                || key.chars().anyMatch(Character::isISOControl)) return false;
        String profileId = key.substring(0, UUID_LENGTH);
        try {
            return UUID.fromString(profileId).toString().equals(profileId);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
