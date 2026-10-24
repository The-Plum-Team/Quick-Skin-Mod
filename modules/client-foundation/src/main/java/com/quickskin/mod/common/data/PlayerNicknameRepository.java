package com.quickskin.mod.common.data;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The nicknames the connected server announced for its players, which name tags show instead of
 * account names. Read on the render thread, written by the network handler on the client thread
 * and cleared with the session; bounded like the appearances.
 */
@Environment(EnvType.CLIENT)
public final class PlayerNicknameRepository {
    private static final PlayerNicknameRepository INSTANCE = new PlayerNicknameRepository();
    private static final int MAX_NICKNAMES = 4096;

    private final Map<UUID, String> nicknames = new ConcurrentHashMap<>();

    private PlayerNicknameRepository() {
    }

    public static PlayerNicknameRepository getInstance() {
        return INSTANCE;
    }

    /** The player's nickname on this server, or null when they have none. */
    @Nullable
    public String nickname(@Nullable UUID playerId) {
        return playerId != null ? nicknames.get(playerId) : null;
    }

    /** Records what the server announced; an empty nickname removes the player's. */
    public synchronized void set(UUID playerId, String nickname) {
        if (playerId == null) return;
        if (nickname == null || nickname.isEmpty()) {
            nicknames.remove(playerId);
            return;
        }
        if (!nicknames.containsKey(playerId) && nicknames.size() >= MAX_NICKNAMES) {
            var eldest = nicknames.keySet().iterator();
            if (eldest.hasNext()) nicknames.remove(eldest.next());
        }
        nicknames.put(playerId, nickname);
    }

    public synchronized void clear() {
        nicknames.clear();
    }
}
