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
