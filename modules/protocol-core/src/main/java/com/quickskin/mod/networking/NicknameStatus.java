package com.quickskin.mod.networking;

/** What the server made of a requested nickname, as {@code nickname_result} carries it. */
public enum NicknameStatus {
    /** The nickname is the player's from now on ({@code ""}: none). */
    ACCEPTED(0),
    /** The player already had exactly this nickname. */
    UNCHANGED(1),
    /** It breaks the nickname rules. */
    INVALID(2),
    /** It reads like another player's name or nickname. */
    TAKEN(3),
    /** The player changed their nickname moments ago; it can change after the wait it names. */
    COOLDOWN(4),
    /** The server does not allow nicknames. */
    DISABLED(5),
    /** A moderator locked this player's nickname on the server. */
    LOCKED(6),
    /** An admin gave this player a nickname for now, which shows instead of the one they chose. */
    ASSIGNED(7);

    /**
     * The request id of a notice the server sends its player unasked, when the session becomes
     * ready and when an admin changes their nickname: {@link #ASSIGNED} or {@link #LOCKED} while
     * they cannot change it, {@link #UNCHANGED} while they can. Clients never send this id.
     */
    public static final int NOTICE_REQUEST_ID = 0;

    private final int code;

    NicknameStatus(int code) {
        this.code = code;
    }

    /** The stable wire value. */
    public int code() {
        return code;
    }

    /** The status with this wire value, or null for one a newer server may send. */
    public static NicknameStatus fromCode(int code) {
        for (NicknameStatus status : values()) {
            if (status.code == code) return status;
        }
        return null;
    }
}
