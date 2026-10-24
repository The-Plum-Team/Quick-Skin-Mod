package com.quickskin.mod.networking;

/** The server's answer to a {@code skin_admin} request; its count says how many it touched. */
public enum SkinAdminStatus {
    /** Done: the count is how many players changed (or 1 for a pool change). */
    DONE(0),
    /** The sender is no admin here (permission level 2). */
    NOT_ALLOWED(1),
    /** The server does not hold the skin: its upload never arrived or was refused. */
    MISSING_TEXTURE(2),
    /** The pool has no skin to hand out. */
    POOL_EMPTY(3),
    /** The pool holds as many skins as it may. */
    POOL_FULL(4),
    /** Another pool skin has that name, or the pool already holds that skin. */
    NAME_TAKEN(5),
    /** The server cannot keep more skins loaded for players (its pinned-texture budget). */
    NO_ROOM(6),
    /** The request makes no sense: an unknown pool skin, an invalid name or model. */
    INVALID(7),
    /** Nothing changed: no player had an assigned skin, or none had a skin of their own to swap. */
    NOTHING(8);

    private final int code;

    SkinAdminStatus(int code) {
        this.code = code;
    }

    /** The stable wire value. */
    public int code() {
        return code;
    }

    /** The status with this wire value, or null for one a newer server may send. */
    public static SkinAdminStatus fromCode(int code) {
        for (SkinAdminStatus status : values()) {
            if (status.code == code) return status;
        }
        return null;
    }
}
