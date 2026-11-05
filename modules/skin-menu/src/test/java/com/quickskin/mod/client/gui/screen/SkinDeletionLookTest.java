package com.quickskin.mod.client.gui.screen;

import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.SkinResolution;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Deleting the saved skin wears the next one, unless a CPM model is the look. */
class SkinDeletionLookTest {
    private static final AssetMetadata SKIN = AssetMetadata.forSkin("sha256-" + "a".repeat(64), "skin",
            Path.of("skin.png"), SkinResolution.STANDARD, 128L, "classic", 1L);
    private static final AssetMetadata MODEL = AssetMetadata.forCpmModel("sha256-" + "b".repeat(64), "model",
            Path.of("model.cpmmodel"), 128L, 1L);

    @Test
    void anotherSkinDeletedChangesNothing() {
        assertEquals(SkinDeletionLook.Action.NONE,
                SkinDeletionLook.decide(false, false, List.of(SKIN)).action());
        assertEquals(SkinDeletionLook.Action.NONE,
                SkinDeletionLook.decide(false, true, List.of(SKIN)).action());
    }

    @Test
    void theWornSkinDeletedWearsTheFirstListedSkin() {
        SkinDeletionLook.Outcome outcome = SkinDeletionLook.decide(true, false, List.of(MODEL, SKIN));
        assertEquals(SkinDeletionLook.Action.WEAR, outcome.action());
        assertSame(SKIN, outcome.next());
    }

    @Test
    void withOnlyAModelLeftTheWornSkinGivesWayToIt() {
        SkinDeletionLook.Outcome outcome = SkinDeletionLook.decide(true, false, List.of(MODEL));
        assertEquals(SkinDeletionLook.Action.WEAR, outcome.action());
        assertSame(MODEL, outcome.next());
    }

    @Test
    void whileACpmModelIsTheLookTheNextSkinIsOnlyRemembered() {
        SkinDeletionLook.Outcome outcome = SkinDeletionLook.decide(true, true, List.of(MODEL, SKIN));
        assertEquals(SkinDeletionLook.Action.REMEMBER, outcome.action());
        assertSame(SKIN, outcome.next());
    }

    @Test
    void whileACpmModelIsTheLookNoModelIsEverChosen() {
        SkinDeletionLook.Outcome outcome = SkinDeletionLook.decide(true, true, List.of(MODEL));
        assertEquals(SkinDeletionLook.Action.REMEMBER, outcome.action());
        assertNull(outcome.next());
    }
}
