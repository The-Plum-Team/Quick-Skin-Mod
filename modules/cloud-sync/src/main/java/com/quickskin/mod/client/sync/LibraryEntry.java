package com.quickskin.mod.client.sync;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One skin or cape of the cloud library, or of the local library as the cloud sees it: its
 * content id and the choices that travel with it. {@code rev} is the cloud's revision of the
 * entry (0 for a local one) and {@code deleted} marks a cloud tombstone.
 */
public record LibraryEntry(
        String kind,
        String id,
        String name,
        String model,
        boolean favorite,
        boolean deleted,
        long rev
) {
    public static final String SKIN = "skin";
    public static final String CAPE = "cape";
    public static final int MAX_NAME_LENGTH = 64;
    private static final Pattern CONTENT_ID = Pattern.compile("sha256-[0-9a-f]{64}");

    public LibraryEntry {
        if (!SKIN.equals(kind) && !CAPE.equals(kind)) throw new IllegalArgumentException("kind");
        if (id == null || !CONTENT_ID.matcher(id).matches()) throw new IllegalArgumentException("id");
        if (!deleted && !isValidName(name)) throw new IllegalArgumentException("name");
        if (CAPE.equals(kind)) {
            model = null;
            favorite = false;
        } else if (!deleted) {
            model = normalizeModel(model);
        }
        if (rev < 0) throw new IllegalArgumentException("rev");
    }

    public static LibraryEntry local(String kind, String id, String name, String model, boolean favorite) {
        return new LibraryEntry(kind, id, name, model, favorite, false, 0L);
    }

    public static LibraryEntry tombstone(String kind, String id, long rev) {
        return new LibraryEntry(kind, id, null, null, false, true, rev);
    }

    /** The key of the entry in both libraries: one id may be a skin and a cape at once. */
    public String key() {
        return key(kind, id);
    }

    public static String key(String kind, String id) {
        return kind + ":" + id;
    }

    /** Whether name, model and favourite are the same, whatever the revisions. */
    public boolean sameChoices(LibraryEntry other) {
        return other != null && deleted == other.deleted && favorite == other.favorite
                && Objects.equals(name, other.name) && Objects.equals(model, other.model);
    }

    public LibraryEntry withRev(long newRev) {
        return new LibraryEntry(kind, id, name, model, favorite, deleted, newRev);
    }

    public static boolean isValidName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_NAME_LENGTH) return false;
        if (!name.equals(name.trim())) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isISOControl(c) || "<>:\"/\\|?*".indexOf(c) >= 0) return false;
        }
        return true;
    }

    /** A local file name fitted to what the cloud accepts, or a fallback. */
    public static String fitName(String name, String fallback) {
        if (name == null) return fallback;
        StringBuilder fitted = new StringBuilder();
        for (int i = 0; i < name.length() && fitted.length() < MAX_NAME_LENGTH; i++) {
            char c = name.charAt(i);
            fitted.append(Character.isISOControl(c) || "<>:\"/\\|?*".indexOf(c) >= 0 ? '_' : c);
        }
        String trimmed = fitted.toString().trim();
        return trimmed.isEmpty() || ".".equals(trimmed) || "..".equals(trimmed) ? fallback : trimmed;
    }

    private static String normalizeModel(String model) {
        String lower = model == null ? "auto" : model.toLowerCase(Locale.ROOT);
        return "classic".equals(lower) || "slim".equals(lower) ? lower : "auto";
    }
}
