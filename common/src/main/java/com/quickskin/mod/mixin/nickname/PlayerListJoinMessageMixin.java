package com.quickskin.mod.mixin.nickname;

import com.quickskin.mod.runtime.NicknameDisplay;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The yellow join message names a player with a nickname by both names, "Nick (Account) joined
 * the game", with vanilla's own translations. Vanilla builds it before the player's client can
 * send anything, so it shows the nickname the server saved last time (ADR 0015). Both branches
 * of the message, a player's usual name and one renamed since the last visit, are covered.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListJoinMessageMixin {
    @Redirect(
            method = "placeNewPlayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;getDisplayName()Lnet/minecraft/network/chat/Component;"),
            require = 0,
            expect = 2,
            allow = 2)
    private Component quickskin$nameWithAccountName(ServerPlayer player) {
        return NicknameDisplay.withAccountName(player);
    }
}
