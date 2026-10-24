package com.quickskin.mod.mixin.armor;

import com.quickskin.mod.networking.ServerHiddenArmor;
import net.minecraft.network.protocol.Packet;
//? if >=1.21 {
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
//?}
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Hands every packet a player is sent to {@link ServerHiddenArmor#filter}, which leaves the armour
 * pieces other players hide out of their equipment (ADR 0017). Only a play-phase connection has a
 * player; every other packet, and every packet while nobody hides anything, passes unchanged.
 */
//? if <1.21 {
@Mixin(ServerGamePacketListenerImpl.class)
//?} else {
@Mixin(ServerCommonPacketListenerImpl.class)
//?}
public abstract class ServerEquipmentFilterMixin {
    @ModifyVariable(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), argsOnly = true,
            require = 0, expect = 1, allow = 1)
    private Packet<?> quickskin$leaveOutHiddenArmor(Packet<?> packet) {
        if ((Object) this instanceof ServerGamePacketListenerImpl game) {
            return ServerHiddenArmor.filter(packet, game.player);
        }
        return packet;
    }
}
