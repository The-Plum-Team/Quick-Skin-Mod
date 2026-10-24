package com.quickskin.mod.networking.payloads;

//? if >=1.21 {
import com.quickskin.mod.networking.TextureTransferLimits;
import com.quickskin.mod.platform.QuickSkinInfo;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * C2S, from an admin's client: a {@code SkinAdminAction} for some players, with the skin id and
 * model it gives (an uploaded skin) and a pool skin's name, each empty when the action takes none.
 * The server answers with a {@link SkinAdminResultPayload} of the same request number.
 */
public record SkinAdminPayload(int requestId, int action, List<UUID> players, String skinId, String model,
                               String name) implements CustomPacketPayload {
    public static final Type<SkinAdminPayload> TYPE = new Type<>(
            //? if <1.21.11 {
            ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "skin_admin")
            //?} else {
            Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "skin_admin")
            //?}
    );

    public static final StreamCodec<ByteBuf, SkinAdminPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                List<UUID> players = payload.players();
                if (players.size() > TextureTransferLimits.MAX_SKIN_ADMIN_PLAYERS) {
                    throw new EncoderException("Too many players in one request: " + players.size());
                }
                buf.writeInt(payload.requestId());
                buf.writeByte(payload.action());
                buf.writeInt(players.size());
                for (UUID player : players) PayloadCodecs.writeUUID(buf, player);
                PayloadCodecs.writeString(buf, payload.skinId(), TextureTransferLimits.MAX_APPEARANCE_ID_BYTES);
                PayloadCodecs.writeString(buf, payload.model(), TextureTransferLimits.MAX_MODEL_BYTES);
                PayloadCodecs.writeString(buf, payload.name(), 3 * TextureTransferLimits.MAX_POOL_SKIN_NAME_CHARS);
            },
            buf -> {
                int requestId = buf.readInt();
                int action = buf.readUnsignedByte();
                int count = buf.readInt();
                if (count < 0 || count > TextureTransferLimits.MAX_SKIN_ADMIN_PLAYERS) {
                    throw new DecoderException("Invalid player count " + count);
                }
                List<UUID> players = new ArrayList<>(count);
                for (int i = 0; i < count; i++) players.add(PayloadCodecs.readUUID(buf));
                return new SkinAdminPayload(requestId, action, List.copyOf(players),
                        PayloadCodecs.readString(buf, TextureTransferLimits.MAX_APPEARANCE_ID_BYTES),
                        PayloadCodecs.readString(buf, TextureTransferLimits.MAX_MODEL_BYTES),
                        PayloadCodecs.readString(buf, 3 * TextureTransferLimits.MAX_POOL_SKIN_NAME_CHARS));
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
//?}
