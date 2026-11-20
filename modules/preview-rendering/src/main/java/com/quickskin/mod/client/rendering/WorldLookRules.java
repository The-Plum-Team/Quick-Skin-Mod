package com.quickskin.mod.client.rendering;

import org.jetbrains.annotations.Nullable;

/**
 * The rules of {@link WorldLook} that need no game: how long a screen's look holds on the player
 * in the world, and how far a player who does not play the skin menu's pose turns.
 */
final class WorldLookRules {
    private WorldLookRules() {
    }

    /**
     * Whether a look shown by {@code owner} during view {@code shownSession} still holds in view
     * {@code session} with {@code screen} open: while its own screen, or a dialog over it, is open
     * in the same view. Another screen of the view ({@code viewScreen}), such as the skin menu
     * the cape menu goes back to, or no screen at all, ends it.
     */
    static boolean holds(boolean shown, int shownSession, @Nullable Object owner, int session,
                         @Nullable Object screen, boolean viewScreen) {
        return shown && session == shownSession && screen != null && !(viewScreen && screen != owner);
    }

    /**
     * The turn, in degrees about the model's own upright axis, of a player who does not play the
     * skin menu's pose while a screen asks for {@code screenTurn}: that turn, and, as far as it has
     * come (all of it from half a turn), the body brought round from {@code bodyYaw} to face the
     * camera's resting place {@code restYaw}, as the menu's pose has it; all of it scaled by how
     * far the camera has come ({@code settle}, 0 to 1). No turn is asked, no turn is made.
     */
    static float unposedTurn(float restYaw, float bodyYaw, float screenTurn, float settle) {
        if (screenTurn == 0.0f) {
            return 0.0f;
        }
        float share = Math.min(1.0f, Math.abs(screenTurn) / 180.0f);
        float toCamera = wrapDegrees(restYaw - bodyYaw) * share;
        return (toCamera + screenTurn) * Math.max(0.0f, Math.min(1.0f, settle));
    }

    /** {@code degrees} brought between -180 and 180 (-180 itself included, 180 not), as the game's own wrap does. */
    static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped >= 180.0f) {
            wrapped -= 360.0f;
        }
        if (wrapped < -180.0f) {
            wrapped += 360.0f;
        }
        return wrapped;
    }
}
