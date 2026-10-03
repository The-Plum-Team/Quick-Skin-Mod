package com.quickskin.mod.client.compat;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The latest look choice between Quick Skin and CPM, as derived from CPM's persisted selection,
 * Quick Skin's selection and the connection's server model.
 */
class CpmLookTest {
    private static final String TEMMIE = "quickskin/Temmie.cpmmodel";
    private static final String MARIO = "SuperMario.cpmmodel";

    private static CpmLook.Owner decide(String selected, String quickSkinModel, boolean serverAssigned) {
        return CpmLook.decide(true, false, true, selected, quickSkinModel != null, quickSkinModel,
                serverAssigned);
    }

    @Test
    void withoutCpmTheLookIsAlwaysQuickSkins() {
        assertEquals(CpmLook.Owner.QUICK_SKIN,
                CpmLook.decide(false, false, true, MARIO, true, TEMMIE, true));
    }

    @Test
    void aSkinChosenInQuickSkinWinsWhileItsResetIsStillRunning() {
        // A deferred reset or one CPM could not run yet: CPM still holds its old selection.
        assertEquals(CpmLook.Owner.QUICK_SKIN,
                CpmLook.decide(true, true, true, MARIO, false, null, true));
    }

    @Test
    void anUnreadableSelectionOrUncataloguedModelDecidesNothing() {
        assertEquals(CpmLook.Owner.UNKNOWN,
                CpmLook.decide(true, false, false, null, false, null, false));
        assertEquals(CpmLook.Owner.UNKNOWN,
                CpmLook.decide(true, false, true, TEMMIE, true, null, false));
    }

    @Test
    void cpmSelectingQuickSkinsModelIsQuickSkinsModel() {
        assertEquals(CpmLook.Owner.QUICK_SKIN_MODEL, decide(TEMMIE, TEMMIE, false));
        // A forced server model does not change whose selection this is; CPM draws it anyway.
        assertEquals(CpmLook.Owner.QUICK_SKIN_MODEL, decide(TEMMIE, TEMMIE, true));
    }

    @Test
    void anyOtherCpmSelectionIsCpmsOwnChoice() {
        // Chosen in CPM's screen after a Quick Skin skin, or after Quick Skin's model.
        assertEquals(CpmLook.Owner.CPM, decide(MARIO, null, false));
        assertEquals(CpmLook.Owner.CPM, decide(MARIO, TEMMIE, false));
    }

    @Test
    void aServerModelWithoutSelectionIsCpmsWhileAssigned() {
        assertEquals(CpmLook.Owner.CPM, decide(null, null, true));
        assertEquals(CpmLook.Owner.QUICK_SKIN, decide(null, null, false));
    }

    @Test
    void cpmsResetAfterQuickSkinsModelReturnsTheLookToQuickSkin() {
        assertEquals(CpmLook.Owner.QUICK_SKIN, decide(null, TEMMIE, false));
    }

    @Test
    void onlyAModelLookWithholdsTheQuickSkinSkin() {
        assertTrue(CpmLook.Owner.CPM.withholdsQuickSkinSkin());
        assertTrue(CpmLook.Owner.QUICK_SKIN_MODEL.withholdsQuickSkinSkin());
        assertFalse(CpmLook.Owner.QUICK_SKIN.withholdsQuickSkinSkin());
        assertFalse(CpmLook.Owner.UNKNOWN.withholdsQuickSkinSkin());
    }

    @Test
    void aServerModelArrivingWithoutSelectionIsAChoice() {
        CpmLook.Session session = new CpmLook.Session();
        session.observe(null, false, null, () -> false);
        assertFalse(session.serverAssigned);
        session.observe(null, false, new byte[] {1, 2}, () -> false);
        assertTrue(session.serverAssigned);
        // The same data again is no new choice, and stays assigned.
        session.observe(null, false, new byte[] {1, 2}, () -> false);
        assertTrue(session.serverAssigned);
        // The server removes it (/cpm setskin -r): the assignment ends.
        session.observe(null, false, null, () -> false);
        assertFalse(session.serverAssigned);
    }

    @Test
    void theEchoOfTheOwnSelectionIsNoServerChoice() {
        CpmLook.Session session = new CpmLook.Session();
        session.observe(MARIO, false, new byte[] {7}, () -> false);
        assertFalse(session.serverAssigned);
        // Quick Skin's skin reset clears the selection and acknowledges the old echo.
        session.acknowledge(new byte[] {7}, () -> false);
        session.observe(null, false, new byte[] {7}, () -> false);
        assertFalse(session.serverAssigned);
        session.observe(null, false, null, () -> false);
        assertFalse(session.serverAssigned);
    }

    @Test
    void aForcedFlagSeenOnArrivalSurvivesCpmLosingIt() {
        CpmLook.Session session = new CpmLook.Session();
        session.observe(null, false, new byte[] {6}, () -> true);
        assertTrue(session.serverAssigned);
        // CPM forgets the flag when its player cache is cleared; the arrival's answer is kept.
        session.acknowledge(new byte[] {6}, () -> false);
        assertTrue(session.serverAssigned);
    }

    @Test
    void aServerModelKeptAfterTheDropRequestStaysTheLook() {
        CpmLook.Session session = new CpmLook.Session();
        session.observe(null, false, new byte[] {8}, () -> false);
        session.acknowledge(new byte[] {8}, () -> false);
        assertFalse(session.serverAssigned);
        for (int tick = 1; tick < CpmLook.Session.SERVER_KEEP_TICKS; tick++) {
            session.observe(null, false, new byte[] {8}, () -> false);
            assertFalse(session.serverAssigned, "tick " + tick);
        }
        session.observe(null, false, new byte[] {8}, () -> false);
        assertTrue(session.serverAssigned);
        // Lifted later (/cpm setskin -r): the assignment ends.
        session.observe(null, false, null, () -> false);
        assertFalse(session.serverAssigned);
    }

    @Test
    void aServerModelDroppedInTimeIsNoChoice() {
        CpmLook.Session session = new CpmLook.Session();
        session.observe(MARIO, false, new byte[] {2}, () -> false);
        session.acknowledge(new byte[] {2}, () -> false);
        for (int tick = 0; tick < 10; tick++) {
            session.observe(null, false, new byte[] {2}, () -> false);
        }
        session.observe(null, false, null, () -> false);
        for (int tick = 0; tick < 2 * CpmLook.Session.SERVER_KEEP_TICKS; tick++) {
            session.observe(null, false, null, () -> false);
        }
        assertFalse(session.serverAssigned);
    }

    @Test
    void aForcedServerModelOutlivesAResetOfTheSelection() {
        CpmLook.Session session = new CpmLook.Session();
        // Forced while CPM had its own selection; CPM's Reset then removes that selection.
        session.observe(MARIO, false, new byte[] {9}, () -> true);
        session.observe(null, false, new byte[] {9}, () -> true);
        assertTrue(session.serverAssigned);
        // Quick Skin's skin reset cannot drop it either.
        session.acknowledge(new byte[] {9}, () -> true);
        assertTrue(session.serverAssigned);
        // The server lifts the force and sends other data: a new arrival.
        session.observe(null, false, new byte[] {10}, () -> false);
        assertTrue(session.serverAssigned);
        session.acknowledge(new byte[] {10}, () -> false);
        assertFalse(session.serverAssigned);
    }

    @Test
    void cpmsResetOfANonForcedSelectionLeavesNoServerChoice() {
        CpmLook.Session session = new CpmLook.Session();
        AtomicInteger forcedQueries = new AtomicInteger();
        session.observe(MARIO, false, new byte[] {3}, () -> false);
        session.observe(null, false, new byte[] {3}, () -> {
            forcedQueries.incrementAndGet();
            return false;
        });
        assertEquals(1, forcedQueries.get());
        assertFalse(session.serverAssigned);
    }

    @Test
    void nothingArrivingDuringAQuickSkinResetIsAChoice() {
        CpmLook.Session session = new CpmLook.Session();
        session.observe(MARIO, true, new byte[] {4}, () -> false);
        session.observe(null, true, new byte[] {5}, () -> false);
        assertFalse(session.serverAssigned);
    }

    @Test
    void aNewConnectionForgetsTheServerModel() {
        CpmLook.Session session = new CpmLook.Session();
        session.observe(null, false, new byte[] {1}, () -> true);
        assertTrue(session.serverAssigned);
        session.reset();
        assertFalse(session.serverAssigned);
        // The same data sent again by the new connection's server is a new arrival.
        session.observe(null, false, new byte[] {1}, () -> true);
        assertTrue(session.serverAssigned);
    }
}
