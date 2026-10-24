package com.quickskin.mod.networking.payloads;

//? if >=1.21 {
import com.quickskin.mod.networking.NicknameEntry;
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

/**
 * S2C: the nicknames of players online, {@code ""} for a player who has none (any more). The
 * server sends everyone's when the session becomes ready and then each change, at most
 * {@link TextureTransferLimits#MAX_NICKNAME_ENTRIES} players per packet.
 */
public record SyncNicknamesPayload(List<NicknameEntry> entries) implements CustomPacketPayload {
    public static final Type<SyncNicknamesPayload> TYPE = new Type<>(
            //? if <1.21.11 {
            ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "sync_nicknames")
            //?} else {
            Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "sync_nicknames")
            //?}
    );

    public static final StreamCodec<ByteBuf, SyncNicknamesPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                List<NicknameEntry> entries = payload.entries();
                if (entries.size() > TextureTransferLimits.MAX_NICKNAME_ENTRIES) {
                    throw new EncoderException("Too many nicknames in one packet: " + entries.size());
                }
                buf.writeInt(entries.size());
                for (NicknameEntry entry : entries) {
                    PayloadCodecs.writeUUID(buf, entry.playerId());
                    PayloadCodecs.writeString(buf, entry.nickname(), TextureTransferLimits.MAX_NICKNAME_BYTES);
                }
            },
            buf -> {
                int count = buf.readInt();
                if (count < 0 || count > TextureTransferLimits.MAX_NICKNAME_ENTRIES) {
                    throw new DecoderException("Invalid nickname count " + count);
                }
                List<NicknameEntry> entries = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    entries.add(new NicknameEntry(
                            PayloadCodecs.readUUID(buf),
                            PayloadCodecs.readString(buf, TextureTransferLimits.MAX_NICKNAME_BYTES)));
                }
                return new SyncNicknamesPayload(List.copyOf(entries));
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
//?}
