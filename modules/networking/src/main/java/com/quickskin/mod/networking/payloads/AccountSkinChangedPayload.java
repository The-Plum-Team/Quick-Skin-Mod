package com.quickskin.mod.networking.payloads;

//? if >=1.21 {
import com.quickskin.mod.platform.QuickSkinInfo;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}

import java.util.UUID;

/**
 * C2S report that the player uploaded a skin to its own Mojang account. Sent only after a
 * successful, user-initiated upload and only when the negotiated profile supports
 * {@code account-skin-refresh}; the server decides whether to share it with unmodded players.
 */
public record AccountSkinChangedPayload(UUID playerId)
        implements CustomPacketPayload {
    public static final Type<AccountSkinChangedPayload> TYPE = new Type<>(
            //? if <1.21.11 {
            ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "account_skin_changed")
            //?} else {
            Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "account_skin_changed")
            //?}
    );

    public static final StreamCodec<ByteBuf, AccountSkinChangedPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> PayloadCodecs.writeUUID(buf, payload.playerId()),
                    buf -> new AccountSkinChangedPayload(PayloadCodecs.readUUID(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
//?}
