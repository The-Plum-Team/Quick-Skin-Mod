package com.quickskin.mod.client.services;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnimationSlotPolicyTest {

    @Test
    void arrivalsFillFreeSlotsButDoNotDisplaceByArrivalOrder() {
        AnimationSlotPolicy policy = new AnimationSlotPolicy(2, 8, 2);
        assertEquals(AnimationSlotPolicy.Kind.FREE_SLOT,
                policy.plan("first", Set.of()).kind());
        assertEquals(AnimationSlotPolicy.Kind.STATIC_FALLBACK,
                policy.plan("arrival-only", Set.of("first", "second")).kind());
    }

    @Test
    void visibleCandidateReplacesAnAnimationThatWasNeverRendered() {
        AnimationSlotPolicy policy = new AnimationSlotPolicy(2, 8, 2);
        policy.markVisible("candidate");
        AnimationSlotPolicy.Admission admission = policy.plan(
                "candidate", new LinkedHashSet<>(Set.of("unseen-a", "unseen-b")));

        assertEquals(AnimationSlotPolicy.Kind.REPLACE_STALE, admission.kind());
    }

    @Test
    void recentlyVisibleSlotsAreStableWhenMoreThanTheBudgetIsVisible() {
        AnimationSlotPolicy policy = new AnimationSlotPolicy(2, 8, 2);
        policy.markVisible("active-a");
        policy.markVisible("active-b");
        policy.markVisible("candidate");

        assertEquals(AnimationSlotPolicy.Kind.STATIC_FALLBACK,
                policy.plan("candidate", Set.of("active-a", "active-b")).kind());
    }

    @Test
    void markingOncePerTickPlansExactlyAsMarkingOnEveryFrame() {
        // The cape renderer marks a visible cape once per client tick instead of once per frame;
        // a mark records only the tick, so every admission decision must be identical.
        AnimationSlotPolicy everyFrame = new AnimationSlotPolicy(1, 8, 2);
        AnimationSlotPolicy oncePerTick = new AnimationSlotPolicy(1, 8, 2);
        Set<String> active = Set.of("active");

        for (int tick = 0; tick < 8; tick++) {
            if (tick < 3) {
                for (int frame = 0; frame < 5; frame++) everyFrame.markVisible("active");
                oncePerTick.markVisible("active");
            }
            for (int frame = 0; frame < 5; frame++) everyFrame.markVisible("candidate");
            oncePerTick.markVisible("candidate");

            assertEquals(everyFrame.plan("candidate", active), oncePerTick.plan("candidate", active),
                    "tick " + tick);
            everyFrame.advanceTick();
            oncePerTick.advanceTick();
        }
        assertEquals(AnimationSlotPolicy.Kind.REPLACE_STALE,
                oncePerTick.plan("candidate", active).kind());
    }

    @Test
    void offscreenSlotBecomesReplaceableAfterTheGraceWindow() {
        AnimationSlotPolicy policy = new AnimationSlotPolicy(2, 8, 2);
        policy.markVisible("stale");
        policy.markVisible("still-visible");
        policy.advanceTick();
        policy.markVisible("still-visible");
        policy.advanceTick();
        policy.markVisible("still-visible");
        policy.advanceTick();
        policy.markVisible("candidate");

        AnimationSlotPolicy.Admission admission = policy.plan(
                "candidate", Set.of("stale", "still-visible"));
        assertEquals(AnimationSlotPolicy.Kind.REPLACE_STALE, admission.kind());
        assertEquals("stale", admission.victimId());
    }
}
