package com.quickskin.mod.networking;

import java.util.Objects;
import java.util.UUID;

/** One player's nickname in {@code sync_nicknames}; {@code ""} means the player has none. */
public record NicknameEntry(UUID playerId, String nickname) {
    public NicknameEntry {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(nickname, "nickname");
    }
}
