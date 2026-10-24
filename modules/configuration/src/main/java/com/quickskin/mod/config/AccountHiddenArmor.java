package com.quickskin.mod.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The armour pieces each launcher account that plays from this instance hides from everyone,
 * keyed like {@link AccountSkinPreferences} (profile UUID plus exact name), one bit per piece as
 * {@code HiddenArmor} defines them. Accounts that hide nothing are not kept.
 */
public final class AccountHiddenArmor {
    private static final int MAX_ACCOUNTS = 128;
    private static final int ALL_PIECES = 15;

    private Map<String, Integer> hidden = new LinkedHashMap<>();

    /** The pieces the account hides, or none when it hides nothing or cannot be stored. */
    public int hidden(String key) {
        Integer mask = key != null ? hidden.get(key) : null;
        return mask != null ? mask & ALL_PIECES : 0;
    }

    /**
     * Keeps the pieces the account hides.
     *
     * @return whether that changed; false also when the key is invalid
     */
    public boolean set(String key, int mask) {
        if (!AccountSkinPreferences.isKey(key)) return false;
        int pieces = mask & ALL_PIECES;
        Integer previous = hidden.remove(key);
        if (pieces == 0) return previous != null;
        while (hidden.size() >= MAX_ACCOUNTS) {
            hidden.remove(hidden.keySet().iterator().next());
        }
        hidden.put(key, pieces);
        return previous == null || previous != pieces;
    }

    void normalize() {
        if (hidden == null) hidden = new LinkedHashMap<>();
        hidden.entrySet().removeIf(entry -> !AccountSkinPreferences.isKey(entry.getKey())
                || entry.getValue() == null || (entry.getValue() & ALL_PIECES) == 0
                || (entry.getValue() & ~ALL_PIECES) != 0);
        while (hidden.size() > MAX_ACCOUNTS) {
            hidden.remove(hidden.keySet().iterator().next());
        }
    }
}
