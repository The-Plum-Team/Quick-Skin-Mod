//? if >=1.21 && <26.1 {
package com.quickskin.mod.networking;

import dev.architectury.networking.NetworkManager;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/** Payload-era transport implementation. */
public final class PayloadNetworkTransport implements NetworkTransport {
    @Override
    public void init() {
        ModNetworking.init();
    }

    @Override
    public void initClient() {
        ClientNetworking.init();
    }

    @Override
    public boolean canServerReceive(CustomPacketPayload.Type<?> type) {
        return NetworkManager.canServerReceive(type);
    }

    @Override
    public boolean canPlayerReceive(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return NetworkManager.canPlayerReceive(player, type);
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        NetworkManager.sendToServer(payload);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        NetworkManager.sendToPlayer(player, payload);
    }
}
//?}
