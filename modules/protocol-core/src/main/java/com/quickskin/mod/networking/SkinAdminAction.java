package com.quickskin.mod.networking;

/** What an admin's client asks of the server in a {@code skin_admin} request. */
public enum SkinAdminAction {
    /** Gives the players the request's skin, which the client uploaded, for now. */
    APPLY(0),
    /** Gives each player a different skin from the server's pool, at random. */
    RANDOM(1),
    /** Gives the players each other's own skins, at random. */
    SHUFFLE(2),
    /** Takes back the skins an admin gave the players; they show their own again. */
    RESET(3),
    /** Adds the request's skin, which the client uploaded, to the pool under the request's name. */
    POOL_ADD(4),
    /** Removes the pool skin with the request's name. */
    POOL_REMOVE(5);

    private final int code;

    SkinAdminAction(int code) {
        this.code = code;
    }

    /** The stable wire value. */
    public int code() {
        return code;
    }

    /** The action with this wire value, or null for one a newer client may send. */
    public static SkinAdminAction fromCode(int code) {
        for (SkinAdminAction action : values()) {
            if (action.code == code) return action;
        }
        return null;
    }
}
