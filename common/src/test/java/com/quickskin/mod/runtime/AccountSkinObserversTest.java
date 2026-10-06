package com.quickskin.mod.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AccountSkinObserversTest {
    private record Viewer(String name) {
    }

    private final Viewer subject = new Viewer("uploader");
    private final Viewer vanilla = new Viewer("vanilla");
    private final Viewer quickSkin = new Viewer("quick-skin");
    private final Viewer secondVanilla = new Viewer("vanilla-2");

    @Test
    void onlyOtherPlayersWithoutQuickSkinAreRefreshed() {
        List<Viewer> online = List.of(subject, vanilla, quickSkin, secondVanilla);

        List<Viewer> observers = AccountSkinObservers.withoutQuickSkin(
                online, subject, Set.of(quickSkin)::contains);

        assertEquals(List.of(vanilla, secondVanilla), observers);
    }

    @Test
    void theUploaderIsSkippedEvenWithoutQuickSkin() {
        assertEquals(List.of(), AccountSkinObservers.withoutQuickSkin(
                List.of(subject), subject, viewer -> false));
    }

    @Test
    void selectionUsesIdentityNotEquality() {
        Viewer twin = new Viewer("uploader");

        assertEquals(List.of(twin), AccountSkinObservers.withoutQuickSkin(
                List.of(subject, twin), subject, viewer -> false),
                "a distinct player with an equal value is still another player");
    }

    @Test
    void onlyChosenObserversThatTrackTheEntityArePairedAgain() {
        List<Viewer> observers = List.of(vanilla, secondVanilla);
        List<Viewer> seenBy = List.of(quickSkin, secondVanilla, new Viewer("vanilla"));

        assertEquals(List.of(secondVanilla), AccountSkinObservers.pairedObservers(seenBy, observers),
                "a Quick Skin viewer keeps its entity, and an untracked observer gets none");
        assertEquals(List.of(), AccountSkinObservers.pairedObservers(List.of(), observers));
        assertEquals(List.of(vanilla), AccountSkinObservers.pairedObservers(List.of(vanilla, vanilla), observers),
                "each observer is paired at most once");
    }
}
