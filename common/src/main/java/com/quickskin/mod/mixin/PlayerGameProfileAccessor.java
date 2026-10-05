package com.quickskin.mod.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Replaces a player's profile. From authlib 7 (Minecraft 1.21.9) a {@code GameProfile} and its
 * properties are immutable, so new signed textures need a new profile instance.
 */
@Mixin(Player.class)
public interface PlayerGameProfileAccessor {
    @Mutable
    @Accessor("gameProfile")
    void quickskin$setGameProfile(GameProfile profile);
}
