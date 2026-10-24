package com.quickskin.mod.mixin.nickname;

import com.quickskin.mod.runtime.NicknameDisplay;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * The yellow leave message names a player with a nickname by both names, like the join message,
 * and for the same reason replaces the name as the first argument of the message's translation.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerLeaveMessageMixin {
    @Shadow
    public ServerPlayer player;

    @ModifyArg(
            //? if <1.21 {
            method = "onDisconnect",
            //?} else {
            method = "removePlayerFromWorld",
            //?}
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/chat/Component;translatable(Ljava/lang/String;[Ljava/lang/Object;)Lnet/minecraft/network/chat/MutableComponent;"),
            index = 1,
            require = 0,
            expect = 1,
            allow = 1)
    private Object[] quickskin$nameWithAccountName(Object[] args) {
        if (args.length > 0) {
            args[0] = NicknameDisplay.withAccountName(player);
        }
        return args;
    }
}
