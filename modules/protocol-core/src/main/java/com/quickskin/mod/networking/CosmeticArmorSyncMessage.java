package com.quickskin.mod.networking;

import java.util.Arrays;
import java.util.UUID;

/**
 * Cosmetic Armor Reworked's slot sync on Forge 1.20.1, as the bytes of its channel's custom
 * payload: the message index 1, the player's UUID as two longs, the slot (0 for the feet up to 3
 * for the head), whether the slot is hidden for the skin, and the cosmetic item, whose first byte
 * says whether there is one. The server sends one to every player whenever a player's slot
 * changes, and so tells every client what to draw over that player's armour.
 *
 * <p>A cosmetic piece a player hides through Quick Skin is left out of the copies other players
 * receive ({@link #withoutCosmetic}): their Cosmetic Armor then draws the armour piece, which the
 * equipment filter hides in turn if the player hides that too. Anything that does not read as
 * exactly such a message is left alone.
 */
public final class CosmeticArmorSyncMessage {
    /** The channel Cosmetic Armor Reworked sends through on Forge. */
    public static final String CHANNEL = "cosmeticarmorreworked:main";
    /** The index Forge's simple channel writes first for the slot sync. */
    private static final int SYNC = 1;
    /** Index, UUID, slot and hidden flag, before the item. */
    private static final int HEADER = 1 + 16 + 1 + 1;
    private static final int SLOTS = 4;

    /** One slot of one player, as the message names it. */
    public record Slot(UUID player, int slot) {
        /** The armour piece of the slot, as {@link HiddenArmor} names it. */
        public int piece() {
            return 1 << slot;
        }
    }

    private CosmeticArmorSyncMessage() {
    }

    /** The slot a slot sync names, or null when {@code message} is not one. */
    public static Slot read(byte[] message) {
        if (message == null || message.length < HEADER + 1 || (message[0] & 0xFF) != SYNC) {
            return null;
        }
        int slot = message[17];
        int hidden = message[18];
        int hasItem = message[HEADER];
        if (slot < 0 || slot >= SLOTS || (hidden != 0 && hidden != 1) || (hasItem != 0 && hasItem != 1)
                || hasItem == 0 && message.length != HEADER + 1) {
            return null;
        }
        return new Slot(new UUID(readLong(message, 1), readLong(message, 9)), slot);
    }

    /**
     * The same slot sync without its cosmetic item, the slot's hidden flag kept: the message other
     * players receive while the player hides that cosmetic piece. Null when {@code message} is not
     * a slot sync.
     */
    public static byte[] withoutCosmetic(byte[] message) {
        if (read(message) == null) {
            return null;
        }
        byte[] without = Arrays.copyOf(message, HEADER + 1);
        without[HEADER] = 0;
        return without;
    }

    private static long readLong(byte[] bytes, int offset) {
        long value = 0L;
        for (int i = 0; i < 8; i++) {
            value = value << 8 | (bytes[offset + i] & 0xFFL);
        }
        return value;
    }
}
