package com.quickskin.mod.client.gui.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapeEditorRoomTest {
    /** The share of the screen's height the player takes, standing as the editor stands them. */
    private static float playerShare(int screenWidth, int screenHeight) {
        // The editor's own figures: the room starts under the header and the Cape and Elytra
        // buttons, ends a margin over the bottom, and keeps an eighth of its height under the feet
        int standing = screenHeight - 8 - (22 + 8 + 16 + 4);
        float tallest = (standing - 4 - standing / 8) / 2.2f;
        float scale = CapeEditorRoom.playerScale(CapeEditorRoom.width(screenWidth), tallest);
        return 2.0f * scale / screenHeight;
    }

    @Test
    void thePlayerTakesTheSameShareOfAWindowAtAnySize() {
        // GUI scale 3 in a 1909x1008 window and in a 2543x1387 one, then scale 2 in a 1920x1080 one
        float small = playerShare(636, 336);
        assertEquals(small, playerShare(847, 462), 0.03f);
        assertEquals(small, playerShare(960, 540), 0.03f);
        assertTrue(small > 0.6f);
    }

    @Test
    void theRoomGrowsWithTheScreen() {
        assertEquals(127, CapeEditorRoom.width(636));
        assertEquals(169, CapeEditorRoom.width(847));
        assertEquals(64, CapeEditorRoom.width(200));
    }

    @Test
    void thePlayerNeverCrowdsTheRoom() {
        // A tall, narrow window: the height would allow a player wider than the room
        int room = CapeEditorRoom.width(400);
        assertTrue(CapeEditorRoom.playerScale(room, 300.0f) < room);
        // A short one: the height decides
        assertEquals(40.0f, CapeEditorRoom.playerScale(room, 40.0f));
    }
}
