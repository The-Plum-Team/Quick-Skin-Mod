package com.quickskin.mod.config;

import com.quickskin.mod.common.data.Nickname;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The cosmetic nickname of each launcher account that plays from this instance, keyed like
 * {@link AccountSkinPreferences} (profile UUID plus exact name). A nickname is the account's own
 * roleplay name: several accounts sharing an instance keep their own, on every server.
 */
public final class AccountNicknames {
    private static final int MAX_ACCOUNTS = 128;

    private Map<String, String> nicknames = new LinkedHashMap<>();

    /** The account's nickname, or an empty string when it has none or cannot be stored. */
    public String nickname(String key) {
        String nickname = key != null ? nicknames.get(key) : null;
        return nickname != null ? nickname : "";
    }

    /**
     * Gives the account a nickname, typed by the player: it is normalized by {@link Nickname},
     * and a blank one, or the account's own name, removes it.
     *
     * @return whether the stored nickname changed; false also when the key or name is invalid
     */
    public boolean set(String key, String typed) {
        if (!AccountSkinPreferences.isKey(key)) return false;
        String nickname = Nickname.normalize(typed);
        if (nickname == null) return false;
        if (nickname.equals(key.substring(key.indexOf(':') + 1))) nickname = "";
        String previous = nicknames.remove(key);
        if (nickname.isEmpty()) return previous != null;
        while (nicknames.size() >= MAX_ACCOUNTS) {
            nicknames.remove(nicknames.keySet().iterator().next());
        }
        nicknames.put(key, nickname);
        return !nickname.equals(previous);
    }

    void normalize() {
        if (nicknames == null) nicknames = new LinkedHashMap<>();
        nicknames.entrySet().removeIf(entry -> !AccountSkinPreferences.isKey(entry.getKey())
                || entry.getValue() == null || entry.getValue().isEmpty()
                || !Nickname.isCanonical(entry.getValue()));
        while (nicknames.size() > MAX_ACCOUNTS) {
            nicknames.remove(nicknames.keySet().iterator().next());
        }
    }
}
