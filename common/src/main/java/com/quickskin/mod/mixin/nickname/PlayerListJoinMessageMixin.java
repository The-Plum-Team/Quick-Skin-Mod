package com.quickskin.mod.mixin.nickname;

import com.quickskin.mod.runtime.NicknameDisplay;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
//? if >=1.20.2 {
import net.minecraft.server.network.CommonListenerCookie;
//?}
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The yellow join message names a player with a nickname by both names, "Nick (Account) joined
 * the game", with vanilla's own translations. Vanilla builds it before the player's client can
 * send anything, so it shows the nickname the server saved last time (ADR 0015). Both branches
 * of the message, a player's usual name and one renamed since the last visit, are covered.
 *
 * <p>The name is replaced as the first argument of the message's translation. A redirect of the
 * name lookup would stop NeoForge 26.3 from starting: its MixinExtras cannot read a redirect
 * compiled against Mixin 0.17.4, which declares several {@code at} points.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListJoinMessageMixin {
    /** The player whose join message is being built; players join on the server thread only. */
    @Unique
    private ServerPlayer quickskin$joiningPlayer;

    @Inject(method = "placeNewPlayer", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$rememberJoiningPlayer(
            Connection connection,
            ServerPlayer player,
            //? if >=1.20.2 {
            CommonListenerCookie cookie,
            //?}
            CallbackInfo callback) {
        quickskin$joiningPlayer = player;
    }

    @ModifyArg(
            method = "placeNewPlayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/chat/Component;translatable(Ljava/lang/String;[Ljava/lang/Object;)Lnet/minecraft/network/chat/MutableComponent;"),
            index = 1,
            require = 0,
            expect = 2,
            allow = 2)
    private Object[] quickskin$nameWithAccountName(Object[] args) {
        ServerPlayer player = quickskin$joiningPlayer;
        quickskin$joiningPlayer = null;
        if (player != null && args.length > 0) {
            args[0] = NicknameDisplay.withAccountName(player);
        }
        return args;
    }
}
