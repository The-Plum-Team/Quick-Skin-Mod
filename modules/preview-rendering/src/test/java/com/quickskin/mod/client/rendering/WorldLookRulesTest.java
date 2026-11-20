package com.quickskin.mod.client.rendering;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldLookRulesTest {
    private static final Object CAPE_MENU = new Object();
    private static final Object SKIN_MENU = new Object();
    private static final Object DIALOG = new Object();

    @Test
    void theLookHoldsWhileItsOwnScreenIsOpen() {
        assertTrue(WorldLookRules.holds(true, 3, CAPE_MENU, 3, CAPE_MENU, true));
    }

    @Test
    void aDialogOverTheScreenKeepsTheLook() {
        // A delete confirmation over the cape menu is no screen of the view: Cancel there comes back to the look
        assertTrue(WorldLookRules.holds(true, 3, CAPE_MENU, 3, DIALOG, false));
    }

    @Test
    void anotherScreenOfTheViewEndsTheLook() {
        // Done or Cancel back to the skin menu shows what the player wears again
        assertFalse(WorldLookRules.holds(true, 3, CAPE_MENU, 3, SKIN_MENU, true));
    }

    @Test
    void noScreenEndsTheLook() {
        assertFalse(WorldLookRules.holds(true, 3, CAPE_MENU, 3, null, false));
    }

    @Test
    void aLookOfAnEarlierViewNeverHolds() {
        assertFalse(WorldLookRules.holds(true, 3, CAPE_MENU, 4, CAPE_MENU, true));
    }

    @Test
    void noLookShownNeverHolds() {
        assertFalse(WorldLookRules.holds(false, 3, CAPE_MENU, 3, CAPE_MENU, true));
    }

    @Test
    void noTurnAskedLeavesTheGameStance() {
        // The skin menu asks for none: a rider keeps facing where the game has them
        assertEquals(0.0f, WorldLookRules.unposedTurn(90.0f, 10.0f, 0.0f, 1.0f));
    }

    @Test
    void aFullTurnAlsoFacesTheBodyToTheCamera() {
        // Body 30 degrees off the camera's rest: brought round as well as turned
        assertEquals(230.0f, WorldLookRules.unposedTurn(30.0f, 0.0f, 200.0f, 1.0f), 1.0e-4f);
    }

    @Test
    void thePartOfTheTurnSoFarBringsThatShareOfTheBodyRound() {
        // Half way to half a turn: half of the way to facing the camera, 45 of its 90 degrees
        assertEquals(135.0f, WorldLookRules.unposedTurn(90.0f, 0.0f, 90.0f, 1.0f), 1.0e-4f);
    }

    @Test
    void theBodyGoesTheShortWayRound() {
        // A body at 170 and a rest at -170 are 20 degrees apart, not 340
        assertEquals(220.0f, WorldLookRules.unposedTurn(-170.0f, 170.0f, 200.0f, 1.0f), 1.0e-4f);
    }

    @Test
    void theTurnFollowsTheCameraOnItsWay() {
        assertEquals(100.0f, WorldLookRules.unposedTurn(0.0f, 0.0f, 200.0f, 0.5f), 1.0e-4f);
        assertEquals(0.0f, WorldLookRules.unposedTurn(0.0f, 0.0f, 200.0f, 0.0f), 1.0e-4f);
        assertEquals(200.0f, WorldLookRules.unposedTurn(0.0f, 0.0f, 200.0f, 3.0f), 1.0e-4f);
    }

    @Test
    void wrapDegreesMatchesTheGame() {
        assertEquals(-180.0f, WorldLookRules.wrapDegrees(180.0f));
        assertEquals(170.0f, WorldLookRules.wrapDegrees(-190.0f));
        assertEquals(-20.0f, WorldLookRules.wrapDegrees(340.0f));
        assertEquals(10.0f, WorldLookRules.wrapDegrees(730.0f));
    }
}
