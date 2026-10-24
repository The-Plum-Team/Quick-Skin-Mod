package com.quickskin.mod.mixin.nickname;

import com.quickskin.mod.runtime.NicknameDisplay;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The yellow leave message names a player with a nickname by both names, like the join message. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerLeaveMessageMixin {
    @Redirect(
            //? if <1.21 {
            method = "onDisconnect",
            //?} else {
            method = "removePlayerFromWorld",
            //?}
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;getDisplayName()Lnet/minecraft/network/chat/Component;"),
            require = 0,
            expect = 1,
            allow = 1)
    private Component quickskin$nameWithAccountName(ServerPlayer player) {
        return NicknameDisplay.withAccountName(player);
    }
}
