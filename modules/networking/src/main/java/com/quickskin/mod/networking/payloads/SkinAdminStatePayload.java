package com.quickskin.mod.networking.payloads;

//? if >=1.21 {
import com.quickskin.mod.networking.PoolSkinEntry;
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
 * S2C, to admins' clients: the server's skin pool, and the players who have a skin an admin gave
 * them (at most {@link TextureTransferLimits#MAX_ASSIGNED_SKIN_ENTRIES}).
 */
public record SkinAdminStatePayload(List<PoolSkinEntry> pool, List<UUID> assigned) implements CustomPacketPayload {
    public static final Type<SkinAdminStatePayload> TYPE = new Type<>(
            //? if <1.21.11 {
            ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "skin_admin_state")
            //?} else {
            Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "skin_admin_state")
            //?}
    );

    public static final StreamCodec<ByteBuf, SkinAdminStatePayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                if (payload.pool().size() > TextureTransferLimits.MAX_POOL_SKINS
                        || payload.assigned().size() > TextureTransferLimits.MAX_ASSIGNED_SKIN_ENTRIES) {
                    throw new EncoderException("Skin admin state over its bounds");
                }
                buf.writeInt(payload.pool().size());
                for (PoolSkinEntry entry : payload.pool()) {
                    PayloadCodecs.writeString(buf, entry.name(), 3 * TextureTransferLimits.MAX_POOL_SKIN_NAME_CHARS);
                    PayloadCodecs.writeString(buf, entry.skinId(), TextureTransferLimits.MAX_APPEARANCE_ID_BYTES);
                    PayloadCodecs.writeString(buf, entry.model(), TextureTransferLimits.MAX_MODEL_BYTES);
                }
                buf.writeInt(payload.assigned().size());
                for (UUID player : payload.assigned()) PayloadCodecs.writeUUID(buf, player);
            },
            buf -> {
                int poolSize = buf.readInt();
                if (poolSize < 0 || poolSize > TextureTransferLimits.MAX_POOL_SKINS) {
                    throw new DecoderException("Invalid pool size " + poolSize);
                }
                List<PoolSkinEntry> pool = new ArrayList<>(poolSize);
                for (int i = 0; i < poolSize; i++) {
                    pool.add(new PoolSkinEntry(
                            PayloadCodecs.readString(buf, 3 * TextureTransferLimits.MAX_POOL_SKIN_NAME_CHARS),
                            PayloadCodecs.readString(buf, TextureTransferLimits.MAX_APPEARANCE_ID_BYTES),
                            PayloadCodecs.readString(buf, TextureTransferLimits.MAX_MODEL_BYTES)));
                }
                int assignedSize = buf.readInt();
                if (assignedSize < 0 || assignedSize > TextureTransferLimits.MAX_ASSIGNED_SKIN_ENTRIES) {
                    throw new DecoderException("Invalid assigned count " + assignedSize);
                }
                List<UUID> assigned = new ArrayList<>(assignedSize);
                for (int i = 0; i < assignedSize; i++) assigned.add(PayloadCodecs.readUUID(buf));
                return new SkinAdminStatePayload(List.copyOf(pool), List.copyOf(assigned));
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
//?}
