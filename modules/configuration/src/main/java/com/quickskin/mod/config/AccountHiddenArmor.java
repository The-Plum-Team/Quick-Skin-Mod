package com.quickskin.mod.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The armour pieces each launcher account that plays from this instance hides from everyone,
 * keyed like {@link AccountSkinPreferences} (profile UUID plus exact name), one bit per piece as
 * {@code HiddenArmor} defines them, the Cosmetic Armor Reworked pieces four bits up. Accounts that
 * hide nothing are not kept.
 *
 * <p>The cosmetic pieces are kept in a map of their own, by their slot's bit, so a version that
 * knows only the armour pieces reads this configuration as before and keeps them.
 */
public final class AccountHiddenArmor {
    private static final int MAX_ACCOUNTS = 128;
    private static final int ALL_PIECES = 15;

    private Map<String, Integer> hidden = new LinkedHashMap<>();
    private Map<String, Integer> cosmetics = new LinkedHashMap<>();

    /** The pieces the account hides, cosmetic ones included, or none when it cannot be stored. */
    public int hidden(String key) {
        if (key == null) return 0;
        Integer pieces = hidden.get(key);
        Integer cosmetic = cosmetics.get(key);
        return (pieces != null ? pieces & ALL_PIECES : 0) | (cosmetic != null ? (cosmetic & ALL_PIECES) << 4 : 0);
    }

    /**
     * Keeps the pieces the account hides, cosmetic ones included.
     *
     * @return whether that changed; false also when the key is invalid
     */
    public boolean set(String key, int mask) {
        if (!AccountSkinPreferences.isKey(key)) return false;
        boolean changed = keep(hidden, key, mask & ALL_PIECES);
        return keep(cosmetics, key, mask >> 4 & ALL_PIECES) | changed;
    }

    private static boolean keep(Map<String, Integer> accounts, String key, int pieces) {
        Integer previous = accounts.remove(key);
        if (pieces == 0) return previous != null;
        while (accounts.size() >= MAX_ACCOUNTS) {
            accounts.remove(accounts.keySet().iterator().next());
        }
        accounts.put(key, pieces);
        return previous == null || previous != pieces;
    }

    void normalize() {
        if (hidden == null) hidden = new LinkedHashMap<>();
        if (cosmetics == null) cosmetics = new LinkedHashMap<>();
        normalize(hidden);
        normalize(cosmetics);
    }

    private static void normalize(Map<String, Integer> accounts) {
        accounts.entrySet().removeIf(entry -> !AccountSkinPreferences.isKey(entry.getKey())
                || entry.getValue() == null || (entry.getValue() & ALL_PIECES) == 0
                || (entry.getValue() & ~ALL_PIECES) != 0);
        while (accounts.size() > MAX_ACCOUNTS) {
            accounts.remove(accounts.keySet().iterator().next());
        }
    }
}
