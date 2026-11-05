package com.quickskin.mod.networking;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Give, random and shuffle count only the players whose shown skin changed. */
class ServerSkinAssignmentsTallyTest {
    @Test
    void playersWhoAlreadyHadTheSkinAreNotCounted() {
        assertEquals(new ServerSkinAssignments.Outcome(SkinAdminStatus.DONE, 0),
                ServerSkinAssignments.tally(List.of(0, 0)), "a repeated two-player shuffle swaps nobody");
        assertEquals(new ServerSkinAssignments.Outcome(SkinAdminStatus.DONE, 1),
                ServerSkinAssignments.tally(List.of(1, 0)));
    }

    @Test
    void theBudgetIsReportedOnlyWhenNobodyChanged() {
        assertEquals(new ServerSkinAssignments.Outcome(SkinAdminStatus.NO_ROOM, 0),
                ServerSkinAssignments.tally(List.of(-1, 0)));
        assertEquals(new ServerSkinAssignments.Outcome(SkinAdminStatus.DONE, 2),
                ServerSkinAssignments.tally(List.of(1, -1, 1)));
    }
}
