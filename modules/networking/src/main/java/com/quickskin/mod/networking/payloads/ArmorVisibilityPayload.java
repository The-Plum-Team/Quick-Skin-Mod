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
 * C2S: the armour pieces the player hides from everyone, one bit per piece as
 * {@link com.quickskin.mod.networking.HiddenArmor} defines them. Sent only on a negotiated session
 * that supports {@code hidden-armor}, when it negotiates and whenever the player changes it.
 */
public record ArmorVisibilityPayload(UUID playerId, int hidden) implements CustomPacketPayload {
    public static final Type<ArmorVisibilityPayload> TYPE = new Type<>(
            //? if <1.21.11 {
            ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "armor_visibility")
            //?} else {
            Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "armor_visibility")
            //?}
    );

    public static final StreamCodec<ByteBuf, ArmorVisibilityPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                PayloadCodecs.writeUUID(buf, payload.playerId());
                buf.writeByte(payload.hidden());
            },
            buf -> new ArmorVisibilityPayload(PayloadCodecs.readUUID(buf), buf.readUnsignedByte()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
//?}
