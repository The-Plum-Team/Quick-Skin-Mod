package com.quickskin.mod.runtime;

import com.quickskin.mod.mixin.ChunkMapAccessor;
import com.quickskin.mod.mixin.TrackedEntityAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Who sees a player, for the immersive poses (ADR 0019): the players whose connections the game's
 * entity tracker pairs with that player's entity, read through the same accessors as the account
 * skin refresh (ADR 0012). A player without a tracker, or a tracker these accessors cannot read,
 * is seen by nobody, so no pose is told.
 */
final class ImmersivePoseViewers {
    private ImmersivePoseViewers() {
    }

    static Collection<ServerPlayer> of(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) return List.of();
        Object tracked = ((ChunkMapAccessor) level.getChunkSource().chunkMap).quickskin$entityMap().get(player.getId());
        if (!(tracked instanceof TrackedEntityAccessor tracker)) return List.of();
        List<ServerPlayer> viewers = new ArrayList<>();
        for (ServerPlayerConnection connection : List.copyOf(tracker.quickskin$seenBy())) {
            ServerPlayer viewer = connection.getPlayer();
            if (viewer != null) viewers.add(viewer);
        }
        return viewers;
    }
}
