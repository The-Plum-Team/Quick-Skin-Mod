package com.quickskin.mod.client.rendering;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewEquipmentPolicyTest {

    /** Stands in for the previewed player entity, which the scope keys by reference identity. */
    private static final class Subject {
        private final String label;

        Subject(String label) {
            this.label = label;
        }

        @Override
        public boolean equals(Object other) {
            // Deliberately value-equal so the tests can prove the scope keys by identity, not equality:
            // two players are never "equal", but a preview must never suppress the wrong entity either.
            return other instanceof Subject subject && subject.label.equals(label);
        }

        @Override
        public int hashCode() {
            return label.hashCode();
        }

        @Override
        public String toString() {
            return label;
        }
    }

    // ===== the rule ==========================================================================

    @Test
    void everySlotAPlayerCanFillIsSuppressed() {
        // The reported bug was the elytra, but the rule is about the surface: a preview shows the
        // skin and the cape, so a chestplate covering the cape or a helmet covering the face is the
        // same defect one slot over. Narrowing this set is a behaviour change, not a tidy-up.
        assertEquals(EnumSet.allOf(PreviewEquipmentPolicy.Slot.class),
                EnumSet.copyOf(PreviewEquipmentPolicy.suppressedSlots()));

        for (PreviewEquipmentPolicy.Slot slot : PreviewEquipmentPolicy.Slot.values()) {
            assertTrue(PreviewEquipmentPolicy.suppresses(slot), "not suppressed: " + slot);
        }
    }

    @Test
    void theChestIsSuppressedBecauseTheWingsAndTheArmourBothReadIt() {
        // The one slot the reported bug turns on, called out on its own so a narrowing of the set
        // that happened to keep the others cannot pass silently.
        assertTrue(PreviewEquipmentPolicy.suppresses(PreviewEquipmentPolicy.Slot.CHEST));
    }

    @Test
    void aSlotAPlayerCannotFillIsNotSuppressed() {
        // The caller maps an unknown vanilla slot - body and saddle slots exist for other entities
        // on the newer eras - to null rather than guessing, so null must answer "leave it alone".
        assertFalse(PreviewEquipmentPolicy.suppresses(null));
    }

    @Test
    void theSuppressedSetCannotBeMutatedByACaller() {
        Set<PreviewEquipmentPolicy.Slot> slots = PreviewEquipmentPolicy.suppressedSlots();
        assertThrows(UnsupportedOperationException.class, slots::clear);
    }

    // ===== the scope =========================================================================

    @Test
    void nothingIsSuppressedWhileNoPreviewIsDrawing() {
        PreviewEquipmentPolicy.Scope<Subject> scope = new PreviewEquipmentPolicy.Scope<>();

        assertFalse(scope.isActiveFor(new Subject("player")));
        assertEquals(0, scope.openScopes());
    }

    @Test
    void anOpenScopeSuppressesOnlyItsOwnSubject() {
        PreviewEquipmentPolicy.Scope<Subject> scope = new PreviewEquipmentPolicy.Scope<>();
        Subject previewed = new Subject("player");
        Subject other = new Subject("player"); // equal, but a different object

        scope.begin(previewed);
        try {
            assertTrue(scope.isActiveFor(previewed));
            // The in-world player rendered behind the screen must keep its real equipment. Equality
            // is not identity: only the exact entity handed to the preview may read as unequipped.
            assertFalse(scope.isActiveFor(other));
            assertEquals(1, scope.openScopes());
        } finally {
            scope.end(previewed);
        }

        assertFalse(scope.isActiveFor(previewed));
        assertEquals(0, scope.openScopes());
    }

    @Test
    void aNullSubjectNeitherOpensAScopeNorMatchesOne() {
        PreviewEquipmentPolicy.Scope<Subject> scope = new PreviewEquipmentPolicy.Scope<>();

        scope.begin(null);
        assertEquals(0, scope.openScopes());
        assertFalse(scope.isActiveFor(null));

        Subject previewed = new Subject("player");
        scope.begin(previewed);
        try {
            assertFalse(scope.isActiveFor(null));
        } finally {
            scope.end(previewed);
        }
    }

    @Test
    void theScopeNestsAndOnlyClosesWhenFullyUnwound() {
        PreviewEquipmentPolicy.Scope<Subject> scope = new PreviewEquipmentPolicy.Scope<>();
        Subject previewed = new Subject("player");

        scope.begin(previewed);
        scope.begin(previewed);
        assertTrue(scope.isActiveFor(previewed));
        assertEquals(1, scope.openScopes());

        scope.end(previewed);
        assertTrue(scope.isActiveFor(previewed), "the outer scope is still open");

        scope.end(previewed);
        assertFalse(scope.isActiveFor(previewed));
        assertEquals(0, scope.openScopes());
    }

    @Test
    void anUnbalancedEndCannotStrandTheScopeOpenOrDriveItNegative() {
        PreviewEquipmentPolicy.Scope<Subject> scope = new PreviewEquipmentPolicy.Scope<>();
        Subject previewed = new Subject("player");

        scope.end(previewed); // never begun
        assertEquals(0, scope.openScopes());

        scope.begin(previewed);
        scope.end(previewed);
        scope.end(previewed); // one too many
        assertEquals(0, scope.openScopes());
        assertFalse(scope.isActiveFor(previewed));

        // A stuck scope would make the live player read as unequipped for good, so re-opening after
        // an unbalanced close has to still behave.
        scope.begin(previewed);
        assertTrue(scope.isActiveFor(previewed));
        scope.end(previewed);
        assertFalse(scope.isActiveFor(previewed));
    }

    @Test
    void endingWithTheWrongSubjectDoesNotCloseSomeoneElsesScope() {
        PreviewEquipmentPolicy.Scope<Subject> scope = new PreviewEquipmentPolicy.Scope<>();
        Subject previewed = new Subject("previewed");
        Subject stranger = new Subject("stranger");

        scope.begin(previewed);
        scope.end(stranger);

        assertTrue(scope.isActiveFor(previewed));
        scope.end(previewed);
        assertFalse(scope.isActiveFor(previewed));
    }

    @Test
    void anotherThreadNeverSeesTheSuppression() throws Exception {
        // The client draws the preview on its own thread while the integrated server ticks its copy
        // of the player on another. A read from that other thread must get the real equipment even
        // while a preview is mid-draw, which is what confines the scope to the opening thread.
        PreviewEquipmentPolicy.Scope<Subject> scope = new PreviewEquipmentPolicy.Scope<>();
        Subject previewed = new Subject("player");

        scope.begin(previewed);
        try {
            assertTrue(scope.isActiveFor(previewed));

            AtomicBoolean seenElsewhere = new AtomicBoolean(true);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            Thread other = new Thread(() -> {
                try {
                    seenElsewhere.set(scope.isActiveFor(previewed));
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    done.countDown();
                }
            }, "not-the-render-thread");
            other.start();
            assertTrue(done.await(10, TimeUnit.SECONDS), "the other thread never finished");
            other.join();

            assertEquals(null, failure.get());
            assertFalse(seenElsewhere.get(), "a scope opened on one thread leaked to another");
        } finally {
            scope.end(previewed);
        }
    }

    @Test
    void aSecondSubjectCannotHijackAnOpenScope() {
        // Nothing in the mod nests two previews of different players on one thread; if that ever
        // changes, the outer subject must not silently start reading someone else's equipment.
        PreviewEquipmentPolicy.Scope<Subject> scope = new PreviewEquipmentPolicy.Scope<>();
        Subject first = new Subject("first");
        Subject second = new Subject("second");

        scope.begin(first);
        try {
            scope.begin(second);
            assertTrue(scope.isActiveFor(first));
            assertFalse(scope.isActiveFor(second));
            assertEquals(1, scope.openScopes());
        } finally {
            scope.end(second);
            scope.end(first);
        }

        assertFalse(scope.isActiveFor(first));
        assertEquals(0, scope.openScopes());
    }

    @Test
    void anExceptionInTheDrawStillClosesTheScope() {
        // The renderer opens the scope before the entity render and closes it in the matching
        // finally; the render is already wrapped in a catch that falls back to the manual path.
        PreviewEquipmentPolicy.Scope<Subject> scope = new PreviewEquipmentPolicy.Scope<>();
        Subject previewed = new Subject("player");

        assertThrows(IllegalStateException.class, () -> {
            scope.begin(previewed);
            try {
                throw new IllegalStateException("the entity render blew up");
            } finally {
                scope.end(previewed);
            }
        });

        assertFalse(scope.isActiveFor(previewed));
        assertEquals(0, scope.openScopes());
    }
}
