package com.quickskin.mod.networking;

import java.util.Locale;
import java.util.Objects;

/**
 * A skin in a server's skin pool, which admins hand out: its name, its {@code local_skin:} id and
 * model. A name is 1 to {@link TextureTransferLimits#MAX_POOL_SKIN_NAME_CHARS} ASCII letters,
 * digits, {@code _}, {@code -} or {@code .}, so a command can name it as one word; two names that
 * differ only in case are the same name.
 */
public record PoolSkinEntry(String name, String skinId, String model) {
    public PoolSkinEntry {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(skinId, "skinId");
        Objects.requireNonNull(model, "model");
    }

    /** The name, as a key that ignores case. */
    public String key() {
        return key(name);
    }

    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public static boolean isValidName(String name) {
        if (name == null || name.isEmpty() || name.length() > TextureTransferLimits.MAX_POOL_SKIN_NAME_CHARS) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            if (!isNameCharacter(name.charAt(i))) return false;
        }
        return true;
    }

    /**
     * A valid name made from any text, such as a file name or a skin's name in a catalog: other
     * characters become {@code _}, runs of them one, and it is cut to the longest name allowed.
     * Text with nothing usable gives {@code fallback}.
     */
    public static String nameFrom(String text, String fallback) {
        StringBuilder name = new StringBuilder();
        if (text != null) {
            for (int i = 0; i < text.length() && name.length() < TextureTransferLimits.MAX_POOL_SKIN_NAME_CHARS; i++) {
                char c = text.charAt(i);
                if (isNameCharacter(c)) {
                    name.append(c);
                } else if (name.length() > 0 && name.charAt(name.length() - 1) != '_') {
                    name.append('_');
                }
            }
        }
        while (name.length() > 0 && name.charAt(name.length() - 1) == '_') name.setLength(name.length() - 1);
        return name.length() == 0 ? fallback : name.toString();
    }

    private static boolean isNameCharacter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '_' || c == '-' || c == '.';
    }
}
