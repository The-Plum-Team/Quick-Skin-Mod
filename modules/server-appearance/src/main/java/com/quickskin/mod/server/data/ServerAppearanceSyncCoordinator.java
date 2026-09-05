package com.quickskin.mod.server.data;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Bounded, round-robin scheduling for server-to-client appearance control packets.
 *
 * <p>The coordinator contains no Minecraft types. Callers supply one immutable roster view per
 * server tick and resolve each returned target UUID to its current appearance immediately before
 * sending. A session is keyed by both the authenticated UUID and the exact connection object, so
 * delayed cleanup for an old connection cannot remove work owned by its replacement.</p>
 */
public final class ServerAppearanceSyncCoordinator {
    private static final ServerAppearanceSyncCoordinator INSTANCE =
            new ServerAppearanceSyncCoordinator();

    static final int MAX_SESSIONS = 1024;
    static final int MAX_PENDING_DIRECT_NOTIFICATIONS = 4096;
    static final int MAX_ACTIONS_PER_TICK = 64;
    static final int ACTION_INTERVAL_TICKS = 2;
    static final int MAX_REQUEST_IDS_PER_SESSION = 8;
    public static final long SERVER_INITIATED_REQUEST_ID = 0L;

    private final Map<SessionKey, SessionState> sessions = new LinkedHashMap<>();
    private final Deque<SessionKey> roundRobin = new ArrayDeque<>();
    private int pendingDirectNotifications;
    private long tick;

    ServerAppearanceSyncCoordinator() {
    }

    public static ServerAppearanceSyncCoordinator getInstance() {
        return INSTANCE;
    }

    /**
     * Starts, joins, or acknowledges a full appearance snapshot for one exact session.
     *
     * <p>Request ID {@value #SERVER_INITIATED_REQUEST_ID} is reserved for server-initiated work
     * and never produces a completion action. Repeating an in-flight ID does not restart its
     * cursor. A retry of a remembered completed ID schedules only another completion action.</p>
     */
    public synchronized RequestStatus requestSnapshot(
            UUID playerId, Object connection, long requestId) {
        if (playerId == null || connection == null || requestId < 0L) {
            return RequestStatus.REJECTED;
        }

        SessionKey key = new SessionKey(playerId, connection);
        SessionState state = sessions.get(key);
        if (state == null) {
            if (sessions.size() >= MAX_SESSIONS) return RequestStatus.REJECTED;
            state = new SessionState(key);
            sessions.put(key, state);
        }

        if (requestId != SERVER_INITIATED_REQUEST_ID
                && state.completedRequestIds.contains(requestId)) {
            queueCompletionAck(state, requestId);
            schedule(state);
            return RequestStatus.COMPLETED_RETRY;
        }

        if (state.snapshotActive) {
            if (requestId == SERVER_INITIATED_REQUEST_ID) {
                if (state.serverInitiatedSnapshot) return RequestStatus.IN_FLIGHT;
                state.serverInitiatedSnapshot = true;
                resetSnapshotCursor(state);
                return RequestStatus.JOINED_IN_FLIGHT;
            }
            if (state.activeRequestIds.contains(requestId)) {
                return RequestStatus.IN_FLIGHT;
            }
            if (state.activeRequestIds.size() >= MAX_REQUEST_IDS_PER_SESSION) {
                return RequestStatus.REJECTED;
            }
            state.activeRequestIds.add(requestId);
            // A distinct request must observe a complete snapshot of its own. It may share the
            // eventual completion pass, but it cannot inherit a cursor already partway through.
            resetSnapshotCursor(state);
            return RequestStatus.JOINED_IN_FLIGHT;
        }

        state.snapshotActive = true;
        state.serverInitiatedSnapshot = requestId == SERVER_INITIATED_REQUEST_ID;
        if (requestId != SERVER_INITIATED_REQUEST_ID) {
            state.activeRequestIds.add(requestId);
        }
        resetSnapshotCursor(state);
        schedule(state);
        return RequestStatus.STARTED;
    }

    /**
     * Coalesces a changed target into every registered session's direct-notification queue.
     *
     * <p>If the global direct bound cannot admit a target for a session, that session's direct
     * entries are discarded and it falls back to a full snapshot. An already-running snapshot
     * is left alone here: its cursor restarts only when the repository supplies a new revision,
     * so repeated no-op updates cannot starve a large roster forever.</p>
     *
     * @return the number of registered sessions covered by either a coalesced direct entry or a
     *         full snapshot
     */
    public synchronized int notifyAppearance(UUID targetPlayerId) {
        if (targetPlayerId == null) return 0;

        int coveredSessions = 0;
        for (SessionState state : sessions.values()) {
            if (state.snapshotActive) {
                coveredSessions++;
                schedule(state);
                continue;
            }
            if (state.pendingDirectTargets.contains(targetPlayerId)) {
                coveredSessions++;
                schedule(state);
                continue;
            }
            if (pendingDirectNotifications < MAX_PENDING_DIRECT_NOTIFICATIONS) {
                state.pendingDirectTargets.add(targetPlayerId);
                pendingDirectNotifications++;
                coveredSessions++;
                schedule(state);
                continue;
            }

            clearDirectTargets(state);
            state.snapshotActive = true;
            state.serverInitiatedSnapshot = true;
            resetSnapshotCursor(state);
            coveredSessions++;
            schedule(state);
        }
        return coveredSessions;
    }

    /** Requeues one failed appearance send without replaying it to unrelated recipients. */
    public synchronized boolean retryAppearance(
            UUID playerId, Object connection, UUID targetPlayerId) {
        if (playerId == null || connection == null || targetPlayerId == null) return false;
        SessionState state = sessions.get(new SessionKey(playerId, connection));
        if (state == null) return false;
        if (state.snapshotActive
                && targetPlayerId.equals(state.lastSnapshotTarget)
                && state.snapshotCursor > 0) {
            // The handler calls this synchronously after an enqueue failure, before another
            // action can be emitted for the session. Rewind that exact roster slot so snapshot
            // completion cannot overtake its failed transport handoff.
            state.snapshotCursor--;
            state.lastSnapshotTarget = null;
            schedule(state);
            return true;
        }
        if (state.pendingDirectTargets.contains(targetPlayerId)) {
            schedule(state);
            return true;
        }
        if (pendingDirectNotifications < MAX_PENDING_DIRECT_NOTIFICATIONS) {
            state.pendingDirectTargets.add(targetPlayerId);
            pendingDirectNotifications++;
        } else {
            clearDirectTargets(state);
            state.snapshotActive = true;
            state.serverInitiatedSnapshot = true;
            resetSnapshotCursor(state);
        }
        schedule(state);
        return true;
    }

    /**
     * Emits a fair, globally bounded slice for the next server tick.
     *
     * <p>At most one action is emitted for a session on a call, and a session that emitted an
     * action is ineligible for the following tick. Snapshot cursors restart at zero whenever the
     * caller's roster revision changes.</p>
     */
    public synchronized List<Action> tick(RosterView roster) {
        Objects.requireNonNull(roster, "roster");
        tick++;

        List<Action> actions = new ArrayList<>(
                Math.min(MAX_ACTIONS_PER_TICK, roundRobin.size()));
        int sessionsToVisit = roundRobin.size();
        for (int visited = 0;
                visited < sessionsToVisit && actions.size() < MAX_ACTIONS_PER_TICK;
                visited++) {
            SessionKey key = roundRobin.removeFirst();
            SessionState state = sessions.get(key);
            if (state == null) continue;

            state.queued = false;
            if (state.nextEligibleTick > tick) {
                schedule(state);
                continue;
            }

            Action action = nextAction(state, roster);
            if (action != null) {
                actions.add(action);
                state.nextEligibleTick = tick + ACTION_INTERVAL_TICKS;
            }
            if (hasWork(state)) schedule(state);
        }
        return List.copyOf(actions);
    }

    /** Removes pending and remembered state for only the supplied UUID/connection pair. */
    public synchronized void cancelSession(UUID playerId, Object connection) {
        if (playerId == null || connection == null) return;
        SessionState removed = sessions.remove(new SessionKey(playerId, connection));
        if (removed == null) return;
        clearDirectTargets(removed);
        roundRobin.removeIf(key -> key.equals(removed.key));
        removed.queued = false;
    }

    /** Clears all session work and request history during server teardown. */
    public synchronized void cancelAll() {
        sessions.clear();
        roundRobin.clear();
        pendingDirectNotifications = 0;
        tick = 0L;
    }

    synchronized int sessionCount() {
        return sessions.size();
    }

    synchronized int pendingDirectCount() {
        return pendingDirectNotifications;
    }

    synchronized int queuedSessionCount() {
        return roundRobin.size();
    }

    synchronized int snapshotCount() {
        int count = 0;
        for (SessionState state : sessions.values()) {
            if (state.snapshotActive) count++;
        }
        return count;
    }

    synchronized boolean hasSession(UUID playerId, Object connection) {
        return playerId != null && connection != null
                && sessions.containsKey(new SessionKey(playerId, connection));
    }

    private Action nextAction(SessionState state, RosterView roster) {
        if (state.snapshotActive) {
            if (state.rosterRevision != roster.revision()) {
                state.rosterRevision = roster.revision();
                state.snapshotCursor = 0;
            }

            if (state.snapshotCursor < roster.playerIds().size()) {
                UUID targetPlayerId = roster.playerIds().get(state.snapshotCursor++);
                state.lastSnapshotTarget = targetPlayerId;
                return Action.appearance(state.key, targetPlayerId);
            }

            finishSnapshot(state);
            if (!state.pendingCompletionAcks.isEmpty()) {
                return completionAction(state);
            }
        }

        if (!state.pendingCompletionAcks.isEmpty()) {
            return completionAction(state);
        }

        Iterator<UUID> iterator = state.pendingDirectTargets.iterator();
        if (!iterator.hasNext()) return null;
        UUID targetPlayerId = iterator.next();
        iterator.remove();
        pendingDirectNotifications--;
        return Action.appearance(state.key, targetPlayerId);
    }

    private Action completionAction(SessionState state) {
        Iterator<Long> iterator = state.pendingCompletionAcks.iterator();
        long requestId = iterator.next();
        iterator.remove();
        return Action.snapshotComplete(state.key, requestId);
    }

    private void finishSnapshot(SessionState state) {
        if (!state.snapshotActive) return;
        for (Long requestId : state.activeRequestIds) {
            rememberCompletedRequest(state, requestId);
            queueCompletionAck(state, requestId);
        }
        state.activeRequestIds.clear();
        state.snapshotActive = false;
        state.serverInitiatedSnapshot = false;
        resetSnapshotCursor(state);
    }

    private void rememberCompletedRequest(SessionState state, long requestId) {
        state.completedRequestIds.remove(requestId);
        state.completedRequestIds.add(requestId);
        while (state.completedRequestIds.size() > MAX_REQUEST_IDS_PER_SESSION) {
            Iterator<Long> iterator = state.completedRequestIds.iterator();
            iterator.next();
            iterator.remove();
        }
    }

    private void queueCompletionAck(SessionState state, long requestId) {
        if (state.pendingCompletionAcks.remove(requestId)) {
            state.pendingCompletionAcks.add(requestId);
            return;
        }
        while (state.pendingCompletionAcks.size() >= MAX_REQUEST_IDS_PER_SESSION) {
            Iterator<Long> iterator = state.pendingCompletionAcks.iterator();
            iterator.next();
            iterator.remove();
        }
        state.pendingCompletionAcks.add(requestId);
    }

    private void resetSnapshotCursor(SessionState state) {
        state.rosterRevision = Long.MIN_VALUE;
        state.snapshotCursor = 0;
        state.lastSnapshotTarget = null;
    }

    private void clearDirectTargets(SessionState state) {
        pendingDirectNotifications -= state.pendingDirectTargets.size();
        state.pendingDirectTargets.clear();
    }

    private void schedule(SessionState state) {
        if (state.queued || !hasWork(state)) return;
        roundRobin.addLast(state.key);
        state.queued = true;
    }

    private static boolean hasWork(SessionState state) {
        return state.snapshotActive
                || !state.pendingCompletionAcks.isEmpty()
                || !state.pendingDirectTargets.isEmpty();
    }

    public enum RequestStatus {
        STARTED,
        JOINED_IN_FLIGHT,
        IN_FLIGHT,
        COMPLETED_RETRY,
        REJECTED
    }

    public enum ActionType {
        APPEARANCE,
        SNAPSHOT_COMPLETE
    }

    /** One transport-neutral action addressed to an exact player session. */
    public record Action(
            ActionType type,
            UUID playerId,
            Object connection,
            UUID targetPlayerId,
            long requestId
    ) {
        private static Action appearance(SessionKey key, UUID targetPlayerId) {
            return new Action(
                    ActionType.APPEARANCE,
                    key.playerId,
                    key.connection,
                    targetPlayerId,
                    SERVER_INITIATED_REQUEST_ID);
        }

        private static Action snapshotComplete(SessionKey key, long requestId) {
            return new Action(
                    ActionType.SNAPSHOT_COMPLETE,
                    key.playerId,
                    key.connection,
                    null,
                    requestId);
        }
    }

    /** Immutable UUID order paired with the revision that produced it. */
    public record RosterView(long revision, List<UUID> playerIds) {
        public RosterView {
            Objects.requireNonNull(playerIds, "playerIds");
            playerIds = List.copyOf(playerIds);
            for (UUID playerId : playerIds) {
                Objects.requireNonNull(playerId, "playerIds contains null");
            }
        }
    }

    private static final class SessionState {
        private final SessionKey key;
        private final Set<UUID> pendingDirectTargets = new LinkedHashSet<>();
        private final Set<Long> activeRequestIds = new LinkedHashSet<>();
        private final Set<Long> pendingCompletionAcks = new LinkedHashSet<>();
        private final Set<Long> completedRequestIds = new LinkedHashSet<>();
        private boolean snapshotActive;
        private boolean serverInitiatedSnapshot;
        private boolean queued;
        private long rosterRevision = Long.MIN_VALUE;
        private int snapshotCursor;
        private UUID lastSnapshotTarget;
        private long nextEligibleTick;

        private SessionState(SessionKey key) {
            this.key = key;
        }
    }

    /** Identity equality for the connection prevents UUID reuse from crossing sessions. */
    private static final class SessionKey {
        private final UUID playerId;
        private final Object connection;

        private SessionKey(UUID playerId, Object connection) {
            this.playerId = playerId;
            this.connection = connection;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof SessionKey key)) return false;
            return playerId.equals(key.playerId) && connection == key.connection;
        }

        @Override
        public int hashCode() {
            return 31 * playerId.hashCode() + System.identityHashCode(connection);
        }
    }
}
