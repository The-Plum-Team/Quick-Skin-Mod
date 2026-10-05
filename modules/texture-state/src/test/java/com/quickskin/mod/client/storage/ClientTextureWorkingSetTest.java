package com.quickskin.mod.client.storage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientTextureWorkingSetTest {

    @Test
    void recentRenderWorkingSetSurvivesBeforeUnusedLruEntries() {
        ClientTextureWorkingSet<String> workingSet = new ClientTextureWorkingSet<>(8, 2);
        workingSet.markInUse("visible-a");
        workingSet.markInUse("visible-b");

        assertEquals("unused", workingSet.selectEviction(
                List.of("visible-a", "unused", "visible-b")));
    }

    @Test
    void hardLimitStillSelectsAnEntryWhenEverythingIsProtected() {
        ClientTextureWorkingSet<String> workingSet = new ClientTextureWorkingSet<>(8, 2);
        workingSet.markInUse("eldest-visible");
        workingSet.markInUse("newest-visible");

        assertEquals("eldest-visible", workingSet.selectEviction(
                List.of("eldest-visible", "newest-visible")));
    }

    @Test
    void protectionExpiresWithoutRenderTouches() {
        ClientTextureWorkingSet<String> workingSet = new ClientTextureWorkingSet<>(8, 1);
        workingSet.markInUse("stale");
        workingSet.advanceTick();
        workingSet.advanceTick();

        assertEquals("stale", workingSet.selectEviction(List.of("stale", "other")));
        assertEquals(0, workingSet.trackedEntries());
    }

    @Test
    void markingOncePerTickProtectsExactlyAsMarkingOnEveryFrame() {
        // The cape renderer marks a visible cape once per client tick instead of once per frame;
        // a mark records only the tick, so the protection it buys must be identical.
        ClientTextureWorkingSet<String> everyFrame = new ClientTextureWorkingSet<>(8, 2);
        ClientTextureWorkingSet<String> oncePerTick = new ClientTextureWorkingSet<>(8, 2);
        List<String> lruOrder = List.of("cape", "unused");

        for (int tick = 0; tick < 6; tick++) {
            boolean rendered = tick < 2;
            if (rendered) {
                for (int frame = 0; frame < 7; frame++) everyFrame.markInUse("cape");
                oncePerTick.markInUse("cape");
            }
            assertEquals(everyFrame.selectEviction(lruOrder), oncePerTick.selectEviction(lruOrder),
                    "tick " + tick);
            assertEquals(everyFrame.trackedEntries(), oncePerTick.trackedEntries(), "tick " + tick);
            everyFrame.advanceTick();
            oncePerTick.advanceTick();
        }
        assertEquals("cape", oncePerTick.selectEviction(lruOrder), "protection still expires");
    }

    @Test
    void trackingItselfRemainsBoundedAndReleasesRemovedKeys() {
        ClientTextureWorkingSet<String> workingSet = new ClientTextureWorkingSet<>(2, 20);
        workingSet.markInUse("a");
        workingSet.markInUse("b");
        workingSet.markInUse("c");
        assertEquals(2, workingSet.trackedEntries());

        workingSet.forget("b");
        assertEquals(1, workingSet.trackedEntries());
        workingSet.clear();
        assertEquals(0, workingSet.trackedEntries());
    }
}
