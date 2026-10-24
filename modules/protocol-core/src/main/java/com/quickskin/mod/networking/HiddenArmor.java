package com.quickskin.mod.networking;

/**
 * Which armour pieces a player hides from everyone, as one bit per piece. The server leaves a
 * hidden piece out of the equipment it sends other players, so nobody sees it, and the player's
 * own client stops drawing it; the item stays worn and protects as usual.
 */
public final class HiddenArmor {
    public static final int FEET = 1;
    public static final int LEGS = 1 << 1;
    public static final int CHEST = 1 << 2;
    public static final int HEAD = 1 << 3;
    /** Every piece. */
    public static final int ALL = FEET | LEGS | CHEST | HEAD;
    /** No piece. */
    public static final int NONE = 0;

    private HiddenArmor() {
    }

    /** Whether {@code mask} names only armour pieces. */
    public static boolean isValid(int mask) {
        return (mask & ~ALL) == 0;
    }

    /** Whether {@code mask} hides {@code piece}. */
    public static boolean hides(int mask, int piece) {
        return (mask & piece) != 0;
    }

    /** {@code mask} with {@code piece} hidden if it was shown, and shown if it was hidden. */
    public static int toggle(int mask, int piece) {
        return (mask ^ piece) & ALL;
    }
}
