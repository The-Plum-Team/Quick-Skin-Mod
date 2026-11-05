package com.quickskin.mod.networking;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CosmeticArmorSyncMessageTest {
    private static final UUID PLAYER = UUID.fromString("032787a0-29eb-4c7e-a7ab-7e3125ec0754");

    @Test
    void readsTheSlotOfASlotSync() {
        CosmeticArmorSyncMessage.Slot slot = CosmeticArmorSyncMessage.read(sync(1, PLAYER, 3, false, true));
        assertNotNull(slot);
        assertEquals(PLAYER, slot.player());
        assertEquals(3, slot.slot());
        assertEquals(HiddenArmor.HEAD, slot.piece());
        assertEquals(HiddenArmor.FEET, CosmeticArmorSyncMessage.read(sync(1, PLAYER, 0, true, false)).piece());
    }

    @Test
    void leavesOutTheCosmeticItemAndKeepsTheHiddenFlag() {
        byte[] withItem = sync(1, PLAYER, 2, false, true);
        assertArrayEquals(sync(1, PLAYER, 2, false, false), CosmeticArmorSyncMessage.withoutCosmetic(withItem));
        byte[] hiddenForSkin = sync(1, PLAYER, 2, true, true);
        assertArrayEquals(sync(1, PLAYER, 2, true, false), CosmeticArmorSyncMessage.withoutCosmetic(hiddenForSkin));
        byte[] empty = sync(1, PLAYER, 1, false, false);
        assertArrayEquals(empty, CosmeticArmorSyncMessage.withoutCosmetic(empty));
    }

    @Test
    void leavesAloneWhatIsNotASlotSync() {
        // Another message of the channel, such as the hidden-flags sync
        assertNull(CosmeticArmorSyncMessage.read(sync(5, PLAYER, 3, false, true)));
        // A slot past the head, a flag that is no boolean, an empty item with bytes after it
        assertNull(CosmeticArmorSyncMessage.read(sync(1, PLAYER, 4, false, true)));
        byte[] badFlag = sync(1, PLAYER, 3, false, true);
        badFlag[18] = 2;
        assertNull(CosmeticArmorSyncMessage.read(badFlag));
        byte[] trailing = Arrays.copyOf(sync(1, PLAYER, 3, false, false), 21);
        assertNull(CosmeticArmorSyncMessage.read(trailing));
        assertNull(CosmeticArmorSyncMessage.read(Arrays.copyOf(sync(1, PLAYER, 3, false, false), 19)));
        assertNull(CosmeticArmorSyncMessage.read(null));
        assertNull(CosmeticArmorSyncMessage.withoutCosmetic(new byte[]{1}));
    }

    /** A message as Cosmetic Armor writes it; a present item is stood in for by a few bytes. */
    private static byte[] sync(int index, UUID player, int slot, boolean hidden, boolean item) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(index);
            out.writeLong(player.getMostSignificantBits());
            out.writeLong(player.getLeastSignificantBits());
            out.writeByte(slot);
            out.writeBoolean(hidden);
            out.writeBoolean(item);
            if (item) {
                out.write(new byte[]{(byte) 0x8C, 0x06, 1, 0});
            }
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
        return bytes.toByteArray();
    }
}
