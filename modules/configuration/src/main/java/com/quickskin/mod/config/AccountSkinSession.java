package com.quickskin.mod.config;

import com.quickskin.mod.common.data.ContentId;

import java.util.UUID;

/**
 * Decides once per connection whether the skin the server saved for the local player replaces
 * a launcher account that has no skin selection in this instance. Authority is the exact player
 * UUID plus connection object, so a packet of an earlier connection can never decide.
 */
public final class AccountSkinSession {
    private static final String LOCAL_SKIN_PREFIX = "local_skin:";
    private static final AccountSkinSession INSTANCE = new AccountSkinSession();

    private UUID playerId;
    private Object connection;
    private boolean awaiting;
    private boolean adopted;

    AccountSkinSession() {
    }

    public static AccountSkinSession getInstance() {
        return INSTANCE;
    }

    /** An account with a selection of its own never waits for the server's record. */
    public synchronized void begin(UUID playerId, Object connection, boolean hasLocalSelection) {
        this.playerId = playerId;
        this.connection = connection;
        this.awaiting = !hasLocalSelection && playerId != null && connection != null;
        this.adopted = false;
    }

    public synchronized void clear() {
        begin(null, null, true);
    }

    /** A choice the player makes outranks a server record that has not arrived yet. */
    public synchronized void selected() {
        awaiting = false;
    }

    public synchronized boolean awaitingServerState(UUID playerId, Object connection) {
        return awaiting && isSession(playerId, connection);
    }

    /**
     * Ends the wait with the server's record of the local player. Returns true when it carries a
     * saved Quick Skin skin: the caller then shows that record instead of uploading a look.
     */
    public synchronized boolean decide(UUID playerId, Object connection, String serverSkinId) {
        if (!awaitingServerState(playerId, connection)) return false;
        awaiting = false;
        adopted = serverSkinId != null && serverSkinId.startsWith(LOCAL_SKIN_PREFIX)
                && ContentId.parse(serverSkinId.substring(LOCAL_SKIN_PREFIX.length())) != null;
        return adopted;
    }

    /** True while this exact session shows the look the server saved for the local player. */
    public synchronized boolean adopted(UUID playerId, Object connection) {
        return adopted && isSession(playerId, connection);
    }

    private boolean isSession(UUID playerId, Object connection) {
        return playerId != null && playerId.equals(this.playerId)
                && connection != null && connection == this.connection;
    }
}
