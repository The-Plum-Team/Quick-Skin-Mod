package com.quickskin.mod.server.data;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerAppearanceSyncCoordinatorTest {

    @Test
    void snapshotOver256PlayersConvergesAtOneActionEveryTwoTicks() {
        ServerAppearanceSyncCoordinator coordinator =
                new ServerAppearanceSyncCoordinator();
        UUID recipientId = uuid(10_000);
        Object connection = new Object();
        List<UUID> rosterIds = ids(300);
        ServerAppearanceSyncCoordinator.RosterView roster =
                new ServerAppearanceSyncCoordinator.RosterView(1L, rosterIds);

        assertEquals(ServerAppearanceSyncCoordinator.RequestStatus.STARTED,
                coordinator.requestSnapshot(recipientId, connection, 71L));

        List<UUID> emittedTargets = new ArrayList<>();
        List<Integer> appearanceTicks = new ArrayList<>();
        int completionCount = 0;
        for (int tick = 1; tick <= 610; tick++) {
            List<ServerAppearanceSyncCoordinator.Action> actions = coordinator.tick(roster);
            assertTrue(actions.size()
                    <= ServerAppearanceSyncCoordinator.MAX_ACTIONS_PER_TICK);
            for (ServerAppearanceSyncCoordinator.Action action : actions) {
                assertEquals(recipientId, action.playerId());
                assertSame(connection, action.connection());
                if (action.type()
                        == ServerAppearanceSyncCoordinator.ActionType.APPEARANCE) {
                    emittedTargets.add(action.targetPlayerId());
                    appearanceTicks.add(tick);
                } else {
                    assertEquals(71L, action.requestId());
                    completionCount++;
                }
            }
        }

        assertEquals(rosterIds, emittedTargets);
        assertEquals(1, completionCount);
        for (int index = 1; index < appearanceTicks.size(); index++) {
            assertTrue(appearanceTicks.get(index) - appearanceTicks.get(index - 1)
                    >= ServerAppearanceSyncCoordinator.ACTION_INTERVAL_TICKS);
        }
    }

    @Test
    void globalCapAndRoundRobinAdvanceEverySessionFairly() {
        ServerAppearanceSyncCoordinator coordinator =
                new ServerAppearanceSyncCoordinator();
        List<Object> connections = new ArrayList<>();
        int sessionTotal = 100;
        for (int index = 0; index < sessionTotal; index++) {
            Object connection = new Object();
            connections.add(connection);
            assertEquals(ServerAppearanceSyncCoordinator.RequestStatus.STARTED,
                    coordinator.requestSnapshot(
                            uuid(1_000 + index), connection,
                            ServerAppearanceSyncCoordinator.SERVER_INITIATED_REQUEST_ID));
        }
        ServerAppearanceSyncCoordinator.RosterView roster =
                new ServerAppearanceSyncCoordinator.RosterView(2L, ids(2));

        List<ServerAppearanceSyncCoordinator.Action> first = coordinator.tick(roster);
        List<ServerAppearanceSyncCoordinator.Action> second = coordinator.tick(roster);
        List<ServerAppearanceSyncCoordinator.Action> third = coordinator.tick(roster);
        List<ServerAppearanceSyncCoordinator.Action> fourth = coordinator.tick(roster);

        assertEquals(ServerAppearanceSyncCoordinator.MAX_ACTIONS_PER_TICK, first.size());
        assertEquals(sessionTotal - ServerAppearanceSyncCoordinator.MAX_ACTIONS_PER_TICK,
                second.size());
        assertEquals(ServerAppearanceSyncCoordinator.MAX_ACTIONS_PER_TICK, third.size());
        assertEquals(sessionTotal - ServerAppearanceSyncCoordinator.MAX_ACTIONS_PER_TICK,
                fourth.size());

        Map<Object, List<UUID>> perConnection = new HashMap<>();
        for (List<ServerAppearanceSyncCoordinator.Action> tickActions
                : List.of(first, second, third, fourth)) {
            assertTrue(tickActions.size()
                    <= ServerAppearanceSyncCoordinator.MAX_ACTIONS_PER_TICK);
            for (ServerAppearanceSyncCoordinator.Action action : tickActions) {
                perConnection.computeIfAbsent(
                        action.connection(), ignored -> new ArrayList<>())
                        .add(action.targetPlayerId());
            }
        }
        assertEquals(sessionTotal, perConnection.size());
        for (Object connection : connections) {
            assertEquals(roster.playerIds(), perConnection.get(connection));
        }
    }

    @Test
    void sessionAndDirectBoundsDegradeOverflowToSnapshot() {
        ServerAppearanceSyncCoordinator coordinator =
                new ServerAppearanceSyncCoordinator();
        for (int index = 0; index < ServerAppearanceSyncCoordinator.MAX_SESSIONS; index++) {
            assertEquals(ServerAppearanceSyncCoordinator.RequestStatus.STARTED,
                    coordinator.requestSnapshot(
                            uuid(index), new Object(),
                            ServerAppearanceSyncCoordinator.SERVER_INITIATED_REQUEST_ID));
        }
        assertEquals(ServerAppearanceSyncCoordinator.MAX_SESSIONS,
                coordinator.sessionCount());
        assertEquals(ServerAppearanceSyncCoordinator.RequestStatus.REJECTED,
                coordinator.requestSnapshot(
                        uuid(ServerAppearanceSyncCoordinator.MAX_SESSIONS + 1),
                        new Object(),
                        ServerAppearanceSyncCoordinator.SERVER_INITIATED_REQUEST_ID));

        // Empty server snapshots finish silently but leave every session registered for fan-out.
        assertTrue(coordinator.tick(
                new ServerAppearanceSyncCoordinator.RosterView(1L, List.of())).isEmpty());
        assertEquals(ServerAppearanceSyncCoordinator.MAX_SESSIONS,
                coordinator.sessionCount());
        assertEquals(0, coordinator.snapshotCount());

        UUID firstTarget = uuid(20_000);
        assertEquals(ServerAppearanceSyncCoordinator.MAX_SESSIONS,
                coordinator.notifyAppearance(firstTarget));
        assertEquals(ServerAppearanceSyncCoordinator.MAX_SESSIONS,
                coordinator.pendingDirectCount());
        coordinator.notifyAppearance(firstTarget);
        assertEquals(ServerAppearanceSyncCoordinator.MAX_SESSIONS,
                coordinator.pendingDirectCount());

        int uniqueTarget = 20_001;
        while (coordinator.snapshotCount() == 0) {
            coordinator.notifyAppearance(uuid(uniqueTarget++));
            assertTrue(coordinator.pendingDirectCount()
                    <= ServerAppearanceSyncCoordinator.MAX_PENDING_DIRECT_NOTIFICATIONS);
        }

        assertTrue(coordinator.snapshotCount() > 0);
        assertTrue(coordinator.pendingDirectCount()
                <= ServerAppearanceSyncCoordinator.MAX_PENDING_DIRECT_NOTIFICATIONS);
        assertTrue(coordinator.queuedSessionCount()
                <= ServerAppearanceSyncCoordinator.MAX_SESSIONS);
    }

    @Test
    void duplicateRequestAndCompletedRetryDoNotReplaySnapshot() {
        ServerAppearanceSyncCoordinator coordinator =
                new ServerAppearanceSyncCoordinator();
        UUID recipientId = uuid(30_000);
        Object connection = new Object();
        ServerAppearanceSyncCoordinator.RosterView roster =
                new ServerAppearanceSyncCoordinator.RosterView(5L, ids(2));

        assertEquals(ServerAppearanceSyncCoordinator.RequestStatus.STARTED,
                coordinator.requestSnapshot(recipientId, connection, 99L));
        assertEquals(ServerAppearanceSyncCoordinator.RequestStatus.IN_FLIGHT,
                coordinator.requestSnapshot(recipientId, connection, 99L));

        List<ServerAppearanceSyncCoordinator.Action> initial = drain(coordinator, roster, 10);
        assertEquals(2, initial.stream().filter(action -> action.type()
                == ServerAppearanceSyncCoordinator.ActionType.APPEARANCE).count());
        assertEquals(1, initial.stream().filter(action -> action.type()
                == ServerAppearanceSyncCoordinator.ActionType.SNAPSHOT_COMPLETE).count());

        assertEquals(ServerAppearanceSyncCoordinator.RequestStatus.COMPLETED_RETRY,
                coordinator.requestSnapshot(recipientId, connection, 99L));
        assertEquals(ServerAppearanceSyncCoordinator.RequestStatus.COMPLETED_RETRY,
                coordinator.requestSnapshot(recipientId, connection, 99L));
        List<ServerAppearanceSyncCoordinator.Action> retry = drain(coordinator, roster, 4);

        assertEquals(0, retry.stream().filter(action -> action.type()
                == ServerAppearanceSyncCoordinator.ActionType.APPEARANCE).count());
        assertEquals(1, retry.size());
        assertEquals(ServerAppearanceSyncCoordinator.ActionType.SNAPSHOT_COMPLETE,
                retry.get(0).type());
        assertEquals(99L, retry.get(0).requestId());
    }

    @Test
    void serverInitiatedSnapshotCompletesWithoutAcknowledgement() {
        ServerAppearanceSyncCoordinator coordinator =
                new ServerAppearanceSyncCoordinator();
        assertEquals(ServerAppearanceSyncCoordinator.RequestStatus.STARTED,
                coordinator.requestSnapshot(
                        uuid(40_000), new Object(),
                        ServerAppearanceSyncCoordinator.SERVER_INITIATED_REQUEST_ID));

        List<ServerAppearanceSyncCoordinator.Action> actions = drain(
                coordinator,
                new ServerAppearanceSyncCoordinator.RosterView(8L, ids(3)),
                12);

        assertEquals(3, actions.size());
        assertTrue(actions.stream().allMatch(action -> action.type()
                == ServerAppearanceSyncCoordinator.ActionType.APPEARANCE));
        assertEquals(0, coordinator.snapshotCount());
        assertEquals(1, coordinator.sessionCount());
    }

    @Test
    void changedRosterRevisionRestartsCursorAndThenConverges() {
        ServerAppearanceSyncCoordinator coordinator =
                new ServerAppearanceSyncCoordinator();
        Object connection = new Object();
        coordinator.requestSnapshot(uuid(50_000), connection, 123L);

        List<ServerAppearanceSyncCoordinator.Action> actions = new ArrayList<>();
        actions.addAll(coordinator.tick(
                new ServerAppearanceSyncCoordinator.RosterView(
                        1L, List.of(uuid(1), uuid(2), uuid(3)))));
        coordinator.tick(new ServerAppearanceSyncCoordinator.RosterView(
                1L, List.of(uuid(1), uuid(2), uuid(3))));
        actions.addAll(coordinator.tick(
                new ServerAppearanceSyncCoordinator.RosterView(
                        2L, List.of(uuid(4), uuid(5)))));
        actions.addAll(drain(
                coordinator,
                new ServerAppearanceSyncCoordinator.RosterView(
                        2L, List.of(uuid(4), uuid(5))),
                8));

        List<UUID> targets = actions.stream()
                .filter(action -> action.type()
                        == ServerAppearanceSyncCoordinator.ActionType.APPEARANCE)
                .map(ServerAppearanceSyncCoordinator.Action::targetPlayerId)
                .toList();
        assertEquals(List.of(uuid(1), uuid(4), uuid(5)), targets);
        assertEquals(1, actions.stream().filter(action -> action.type()
                == ServerAppearanceSyncCoordinator.ActionType.SNAPSHOT_COMPLETE).count());
    }

    @Test
    void unchangedNotificationsCannotStarveLargeSnapshot() {
        ServerAppearanceSyncCoordinator coordinator =
                new ServerAppearanceSyncCoordinator();
        UUID recipient = uuid(55_000);
        Object connection = new Object();
        List<UUID> rosterIds = ids(40);
        ServerAppearanceSyncCoordinator.RosterView roster =
                new ServerAppearanceSyncCoordinator.RosterView(7L, rosterIds);
        coordinator.requestSnapshot(recipient, connection, 321L);

        List<ServerAppearanceSyncCoordinator.Action> actions = new ArrayList<>();
        for (int tick = 0; tick < 100; tick++) {
            // Models an accepted no-op update whose repository revision remains unchanged.
            coordinator.notifyAppearance(rosterIds.get(0));
            List<ServerAppearanceSyncCoordinator.Action> tickActions =
                    coordinator.tick(roster);
            actions.addAll(tickActions);
            if (tickActions.stream().anyMatch(action -> action.type()
                    == ServerAppearanceSyncCoordinator.ActionType.SNAPSHOT_COMPLETE)) break;
        }

        List<UUID> targets = actions.stream()
                .filter(action -> action.type()
                        == ServerAppearanceSyncCoordinator.ActionType.APPEARANCE)
                .map(ServerAppearanceSyncCoordinator.Action::targetPlayerId)
                .toList();
        assertEquals(rosterIds, targets);
        assertEquals(1, actions.stream().filter(action -> action.type()
                == ServerAppearanceSyncCoordinator.ActionType.SNAPSHOT_COMPLETE).count());
    }

    @Test
    void exactOldSessionCancellationCannotRemoveReplacement() {
        ServerAppearanceSyncCoordinator coordinator =
                new ServerAppearanceSyncCoordinator();
        UUID playerId = uuid(60_000);
        Object oldConnection = new Object();
        Object replacementConnection = new Object();

        coordinator.requestSnapshot(
                playerId, oldConnection,
                ServerAppearanceSyncCoordinator.SERVER_INITIATED_REQUEST_ID);
        coordinator.requestSnapshot(
                playerId, replacementConnection,
                ServerAppearanceSyncCoordinator.SERVER_INITIATED_REQUEST_ID);
        coordinator.notifyAppearance(uuid(61_000));

        coordinator.cancelSession(playerId, oldConnection);

        assertFalse(coordinator.hasSession(playerId, oldConnection));
        assertTrue(coordinator.hasSession(playerId, replacementConnection));
        assertEquals(1, coordinator.sessionCount());

        List<ServerAppearanceSyncCoordinator.Action> actions = drain(
                coordinator,
                new ServerAppearanceSyncCoordinator.RosterView(
                        11L, List.of(uuid(62_000))),
                8);
        assertFalse(actions.isEmpty());
        assertTrue(actions.stream().allMatch(
                action -> action.connection() == replacementConnection));
        assertTrue(actions.stream().noneMatch(
                action -> action.connection() == oldConnection));

        coordinator.cancelSession(playerId, oldConnection);
        assertTrue(coordinator.hasSession(playerId, replacementConnection));
        coordinator.cancelAll();
        assertEquals(0, coordinator.sessionCount());
        assertEquals(0, coordinator.pendingDirectCount());
        assertEquals(0, coordinator.queuedSessionCount());
    }

    @Test
    void failedAppearanceHandoffIsRetriedForTheExactSession() {
        ServerAppearanceSyncCoordinator coordinator =
                new ServerAppearanceSyncCoordinator();
        UUID recipient = uuid(70_000);
        UUID target = uuid(70_001);
        Object connection = new Object();
        coordinator.requestSnapshot(
                recipient, connection,
                ServerAppearanceSyncCoordinator.SERVER_INITIATED_REQUEST_ID);
        ServerAppearanceSyncCoordinator.RosterView roster =
                new ServerAppearanceSyncCoordinator.RosterView(1L, List.of(target));

        ServerAppearanceSyncCoordinator.Action failed = coordinator.tick(roster).get(0);
        assertTrue(coordinator.retryAppearance(
                failed.playerId(), failed.connection(), failed.targetPlayerId()));
        List<ServerAppearanceSyncCoordinator.Action> retried =
                drain(coordinator, roster, 4);

        assertEquals(1, retried.size());
        assertEquals(target, retried.get(0).targetPlayerId());
        assertSame(connection, retried.get(0).connection());
    }

    @Test
    void failedSnapshotHandoffIsRetriedBeforeExactCompletion() {
        ServerAppearanceSyncCoordinator coordinator =
                new ServerAppearanceSyncCoordinator();
        UUID recipient = uuid(71_000);
        UUID target = uuid(71_001);
        Object connection = new Object();
        coordinator.requestSnapshot(recipient, connection, 880L);
        ServerAppearanceSyncCoordinator.RosterView roster =
                new ServerAppearanceSyncCoordinator.RosterView(1L, List.of(target));

        ServerAppearanceSyncCoordinator.Action failed = coordinator.tick(roster).get(0);
        assertTrue(coordinator.retryAppearance(
                failed.playerId(), failed.connection(), failed.targetPlayerId()));
        List<ServerAppearanceSyncCoordinator.Action> retried =
                drain(coordinator, roster, 6);

        assertEquals(2, retried.size());
        assertEquals(ServerAppearanceSyncCoordinator.ActionType.APPEARANCE,
                retried.get(0).type());
        assertEquals(target, retried.get(0).targetPlayerId());
        assertEquals(ServerAppearanceSyncCoordinator.ActionType.SNAPSHOT_COMPLETE,
                retried.get(1).type());
        assertEquals(880L, retried.get(1).requestId());
    }

    private static List<ServerAppearanceSyncCoordinator.Action> drain(
            ServerAppearanceSyncCoordinator coordinator,
            ServerAppearanceSyncCoordinator.RosterView roster,
            int ticks
    ) {
        List<ServerAppearanceSyncCoordinator.Action> actions = new ArrayList<>();
        for (int index = 0; index < ticks; index++) {
            actions.addAll(coordinator.tick(roster));
        }
        return actions;
    }

    private static List<UUID> ids(int count) {
        List<UUID> ids = new ArrayList<>(count);
        for (int index = 0; index < count; index++) ids.add(uuid(index));
        return List.copyOf(ids);
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value + 1L);
    }
}
