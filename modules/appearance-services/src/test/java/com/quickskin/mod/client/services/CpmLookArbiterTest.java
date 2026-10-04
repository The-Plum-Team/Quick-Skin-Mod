package com.quickskin.mod.client.services;

import com.quickskin.mod.client.compat.CpmLook.Owner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CpmLookArbiterTest {
    @Test
    void aKnownChoiceNowWinsOverAStaleDecision() {
        assertFalse(CpmLookArbiter.cpmModelIsTheLook(Owner.QUICK_SKIN, Owner.CPM));
        assertTrue(CpmLookArbiter.cpmModelIsTheLook(Owner.CPM, Owner.QUICK_SKIN));
        assertTrue(CpmLookArbiter.cpmModelIsTheLook(Owner.QUICK_SKIN_MODEL, null));
    }

    @Test
    void anUnknownChoiceKeepsTheLatestDecision() {
        assertTrue(CpmLookArbiter.cpmModelIsTheLook(Owner.UNKNOWN, Owner.CPM));
        assertTrue(CpmLookArbiter.cpmModelIsTheLook(Owner.UNKNOWN, Owner.QUICK_SKIN_MODEL));
        assertFalse(CpmLookArbiter.cpmModelIsTheLook(Owner.UNKNOWN, Owner.QUICK_SKIN));
    }

    @Test
    void theLatestDecisionAloneIsWhatEveryFramePreviewsRead() {
        assertTrue(CpmLookArbiter.cpmModelIsTheLook(null, Owner.CPM));
        assertFalse(CpmLookArbiter.cpmModelIsTheLook(null, Owner.QUICK_SKIN));
    }

    @Test
    void nothingDecidedIsQuickSkinsLookAsOnTheWorldPlayer() {
        assertFalse(CpmLookArbiter.cpmModelIsTheLook(null, null));
        assertFalse(CpmLookArbiter.cpmModelIsTheLook(Owner.UNKNOWN, null));
    }
}
