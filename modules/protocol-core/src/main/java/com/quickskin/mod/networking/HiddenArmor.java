package com.quickskin.mod.networking;

/**
 * Which armour pieces a player hides from everyone, as one bit per piece. The server leaves a
 * hidden piece out of the equipment it sends other players, so nobody sees it, and the player's
 * own client stops drawing it; the item stays worn and protects as usual.
 *
 * <p>With Cosmetic Armor Reworked a slot may also hold a cosmetic piece, drawn over the armour
 * piece. Its bit is the armour piece's shifted by four ({@link #cosmetic}): a hidden cosmetic
 * piece is left out of that mod's slot sync, so the armour piece shows in its place.
 */
public final class HiddenArmor {
    public static final int FEET = 1;
    public static final int LEGS = 1 << 1;
    public static final int CHEST = 1 << 2;
    public static final int HEAD = 1 << 3;
    /** Every piece. */
    public static final int ALL = FEET | LEGS | CHEST | HEAD;
    /** Every cosmetic piece. */
    public static final int COSMETICS = ALL << 4;
    /** No piece. */
    public static final int NONE = 0;

    private HiddenArmor() {
    }

    /** Whether {@code mask} names only armour pieces. */
    public static boolean isValid(int mask) {
        return (mask & ~ALL) == 0;
    }

    /** Whether {@code mask} names only armour and cosmetic pieces. */
    public static boolean isValidWithCosmetics(int mask) {
        return (mask & ~(ALL | COSMETICS)) == 0;
    }

    /** The cosmetic piece over the armour piece {@code piece}. */
    public static int cosmetic(int piece) {
        return (piece & ALL) << 4;
    }

    /** The armour piece of a cosmetic or armour piece: the slot both are in. */
    public static int slot(int piece) {
        return (piece | piece >> 4) & ALL;
    }

    /** Whether {@code piece} is a cosmetic piece. */
    public static boolean isCosmetic(int piece) {
        return piece != NONE && (piece & COSMETICS) == piece;
    }

    /** Whether {@code mask} hides {@code piece}. */
    public static boolean hides(int mask, int piece) {
        return (mask & piece) != 0;
    }

    /** {@code mask} with {@code piece} hidden if it was shown, and shown if it was hidden. */
    public static int toggle(int mask, int piece) {
        return (mask ^ piece) & (ALL | COSMETICS);
    }
}
