package com.quickskin.mod.mixin.armor;

import com.quickskin.mod.networking.ServerHiddenArmor;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Hands every packet a play connection sends to {@link ServerHiddenArmor#filterCosmetics}, which
 * leaves the Cosmetic Armor Reworked pieces other players hide out of that mod's slot sync (ADR
 * 0017). Forge 1.20.1's packet distributor sends to one player straight through the connection,
 * past {@code ServerEquipmentFilterMixin}, and that mod sends a player's slots so when another
 * player joins and on an integrated server. Every other packet passes unchanged.
 */
@Mixin(Connection.class)
public abstract class ConnectionCosmeticArmorFilterMixin {
    @ModifyVariable(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At("HEAD"), argsOnly = true, require = 0, expect = 1, allow = 1)
    private Packet<?> quickskin$leaveOutHiddenCosmetics(Packet<?> packet) {
        if (((Connection) (Object) this).getPacketListener() instanceof ServerGamePacketListenerImpl game) {
            return ServerHiddenArmor.filterCosmetics(packet, game.player);
        }
        return packet;
    }
}
