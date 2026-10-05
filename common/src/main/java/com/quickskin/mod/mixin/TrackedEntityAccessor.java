package com.quickskin.mod.mixin;

import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.network.ServerPlayerConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

/** The pairing state of one tracked entity: its server entity and the connections that see it. */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public interface TrackedEntityAccessor {
    @Accessor("serverEntity")
    ServerEntity quickskin$serverEntity();

    @Accessor("seenBy")
    Set<ServerPlayerConnection> quickskin$seenBy();
}
