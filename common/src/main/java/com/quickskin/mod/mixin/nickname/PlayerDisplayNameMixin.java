package com.quickskin.mod.mixin.nickname;

import com.quickskin.mod.runtime.NicknameDisplay;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * A player with a nickname is called by it wherever their display name shows: on the server in
 * chat, death and advancement messages and command feedback, and on a Quick Skin client on the
 * name tag above their head. Only the name inside the team formatting changes, so team colours,
 * prefixes and the click and hover that name the account stay, and commands keep the account
 * name (ADR 0015). Forge and NeoForge pass their cached {@code NameFormat} name here, which the
 * nickname replaces only while no other mod renamed the player.
 */
@Mixin(Player.class)
public abstract class PlayerDisplayNameMixin {
    @ModifyArg(
            method = "getDisplayName",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/scores/PlayerTeam;formatNameForTeam(Lnet/minecraft/world/scores/Team;Lnet/minecraft/network/chat/Component;)Lnet/minecraft/network/chat/MutableComponent;"),
            index = 1,
            require = 0,
            expect = 1,
            allow = 1)
    private Component quickskin$nickname(Component name) {
        return NicknameDisplay.displayName((Player) (Object) this, name);
    }
}
