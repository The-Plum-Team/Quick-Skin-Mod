package com.quickskin.mod.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the entity trackers so a refreshed player can be paired again for chosen observers. */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    /** Values are {@code ChunkMap.TrackedEntity}, which is not visible outside its package. */
    @Accessor("entityMap")
    Int2ObjectMap<?> quickskin$entityMap();
}
