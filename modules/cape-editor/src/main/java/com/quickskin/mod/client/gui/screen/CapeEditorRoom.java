package com.quickskin.mod.client.gui.screen;

/**
 * The room the player stands in while the cape editor floats round them, between the canvas and
 * the right panel, and how large the player stands there. Both go by the screen alone, so the
 * editor looks the same in any window and at any GUI scale: the size of the cape menu's model,
 * which is set in GUI pixels for that menu's own scale, has no say in this room.
 */
final class CapeEditorRoom {
    private static final int MIN_WIDTH = 64;
    /** The player's arms span at most this share of the room's width. */
    private static final float FILL = 0.875f;

    private CapeEditorRoom() {
    }

    /** The room's width on a screen this wide: a fifth of it. */
    static int width(int screenWidth) {
        return Math.max(MIN_WIDTH, screenWidth / 5);
    }

    /**
     * The scale at which the player fills the room: {@code tallest}, the scale the room's height
     * leaves, unless the arms, which span as many GUI pixels as the scale, would then crowd the
     * canvas and the panel.
     */
    static float playerScale(int roomWidth, float tallest) {
        return Math.min(tallest, roomWidth * FILL);
    }
}
