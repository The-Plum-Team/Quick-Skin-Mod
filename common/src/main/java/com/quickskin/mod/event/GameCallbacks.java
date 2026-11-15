package com.quickskin.mod.event;

import com.quickskin.mod.platform.QuickSkinInfo;

/**
 * Runs Quick Skin's part of a callback the game makes while a player enters, respawns in or
 * leaves a world, so that a failure of Quick Skin's never becomes the game's.
 *
 * <p>These callbacks run inside the game's own handling of the login, respawn and disconnect,
 * where an exception costs the player the world. Seen on Forge 1.20.1: the client's join
 * callback fires before the player is added to the level, and an exception there leaves the
 * game on "Joining world..." forever with one "Error executing task on Client" line and no
 * crash screen; one from the server's join callback sends the player back with "Invalid player
 * data". The world must load, with the failure in the log and that step left out.</p>
 */
public final class GameCallbacks {
    private GameCallbacks() {
    }

    /** Runs {@code action}; a failure is logged with what Quick Skin {@code could not} do. */
    public static void guarded(String couldNot, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError error) {
            QuickSkinInfo.LOGGER.error("Quick Skin could not {}; the game goes on without that step",
                    couldNot, error);
        }
    }
}
