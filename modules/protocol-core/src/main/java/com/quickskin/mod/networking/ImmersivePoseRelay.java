package com.quickskin.mod.networking;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The server's record of the immersive pose each connected player plays, and what it does with a
 * pose a client sends: drops one sent too often, out of order or by a replaced session, keeps a
 * repeat of the same pose (the client's keep-alive) without passing it on, and says when a change
 * is to be relayed. A pose not heard of again within {@link #EXPIRY_NANOS} ends, so a client that
 * stops talking cannot leave its player posed. Bounded by the connected players, each entry
 * belonging to one exact session. Used on the server thread only.
 */
public final class ImmersivePoseRelay {
    /** A pose ends when its client has not repeated it for this long; clients repeat it every 5 s. */
    public static final long EXPIRY_NANOS = 15_000_000_000L;
    /** A session may send this many poses at once, */
    static final double BURST = 10.0;
    /** and this many a second after that. */
    static final double PER_SECOND = 4.0;

    /** What became of a pose a client sent. */
    public enum Outcome {
        /** Dropped: too often, out of order, or malformed. */
        REFUSED,
        /** Kept, but nothing changed for anyone else. */
        UNCHANGED,
        /** A change everyone who sees the player is to be told. */
        RELAY
    }

    private static final class Session {
        final Object connection;
        int sequence;
        ImmersivePose pose = ImmersivePose.OFF;
        long heardNanos;
        double tokens = BURST;
        long tokensAtNanos;

        Session(Object connection, int sequence, long nowNanos) {
            this.connection = connection;
            this.sequence = sequence;
            this.heardNanos = nowNanos;
            this.tokensAtNanos = nowNanos;
        }
    }

    private final Map<UUID, Session> sessions = new HashMap<>();

    /**
     * Takes the pose {@code player}'s session {@code connection} sent as its {@code sequence}-th.
     * The first pose of a session may carry any sequence; each later one must come after it.
     * Ending a pose is never refused for its rate, so the last word of a closing menu is not lost.
     */
    public Outcome accept(UUID player, Object connection, int sequence, ImmersivePose pose, long nowNanos) {
        if (player == null || connection == null || pose == null
                || !ImmersivePose.isValidFromClient(pose.pose(), pose.yaw(), pose.flags(), pose.swaps(), pose.swapVariant())) {
            return Outcome.REFUSED;
        }
        Session session = sessions.get(player);
        if (session == null || session.connection != connection) {
            session = new Session(connection, sequence - 1, nowNanos);
            sessions.put(player, session);
        }
        if (sequence - session.sequence <= 0) return Outcome.REFUSED;
        refill(session, nowNanos);
        if (pose.active()) {
            if (session.tokens < 1.0) return Outcome.REFUSED;
            session.tokens -= 1.0;
        }
        session.sequence = sequence;
        session.heardNanos = nowNanos;
        if (pose.equals(session.pose)) return Outcome.UNCHANGED;
        boolean wasActive = session.pose.active();
        session.pose = pose.active() ? pose : ImmersivePose.OFF;
        return pose.active() || wasActive ? Outcome.RELAY : Outcome.UNCHANGED;
    }

    private static void refill(Session session, long nowNanos) {
        double seconds = Math.max(0L, nowNanos - session.tokensAtNanos) / 1.0e9;
        session.tokens = Math.min(BURST, session.tokens + seconds * PER_SECOND);
        session.tokensAtNanos = nowNanos;
    }

    /** The pose {@code player} plays now, {@link ImmersivePose#OFF} when none. */
    public ImmersivePose current(UUID player) {
        Session session = sessions.get(player);
        return session == null ? ImmersivePose.OFF : session.pose;
    }

    /** Every pose played now, by player, in no particular order. */
    public Map<UUID, ImmersivePose> active() {
        Map<UUID, ImmersivePose> active = new LinkedHashMap<>();
        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            if (entry.getValue().pose.active()) active.put(entry.getKey(), entry.getValue().pose);
        }
        return active;
    }

    public boolean anyActive() {
        for (Session session : sessions.values()) {
            if (session.pose.active()) return true;
        }
        return false;
    }

    /**
     * Ends {@code player}'s pose for the server's own reasons (death, another world), keeping the
     * session's sequence and budget. Returns whether a pose ended.
     */
    public boolean end(UUID player) {
        Session session = sessions.get(player);
        if (session == null || !session.pose.active()) return false;
        session.pose = ImmersivePose.OFF;
        return true;
    }

    /** Ends the poses not heard of again in time and names their players. */
    public List<UUID> expire(long nowNanos) {
        List<UUID> expired = new ArrayList<>();
        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            Session session = entry.getValue();
            if (session.pose.active() && nowNanos - session.heardNanos >= EXPIRY_NANOS) {
                session.pose = ImmersivePose.OFF;
                expired.add(entry.getKey());
            }
        }
        return expired;
    }

    /** The exact session left: returns whether it was playing a pose. */
    public boolean remove(UUID player, Object connection) {
        Session session = sessions.get(player);
        if (session == null || session.connection != connection) return false;
        sessions.remove(player);
        return session.pose.active();
    }

    public void clear() {
        sessions.clear();
    }

    int size() {
        return sessions.size();
    }
}
