package com.quickskin.mod.networking;

/**
 * Worst-case encoded size of every Quick Skin packet against the vanilla custom-payload limits.
 *
 * <p>Every supported Minecraft version refuses a serverbound custom payload above 32767 bytes and a
 * clientbound one above 1 MiB; a frame above 2 MiB breaks the 21-bit length prefix of the vanilla
 * frame codec (Krypton-family decoders report it as {@code VarInt too big}). Quick Skin must never
 * depend on a mod such as XL Packets raising them, so each packet's size is bounded here from the
 * same constants the codecs use, whatever the size of the asset behind it. Two layouts exist:
 * before 1.21 strings and byte arrays carry a VarInt length ({@code FriendlyByteBuf}); from 1.21
 * byte arrays carry a 4-byte length ({@code PayloadCodecs}). The envelope reserve covers the loader
 * channel identifier plus Quick Skin's packet identifier around the payload.</p>
 */
public final class WirePayloadBudget {
    public static final int VANILLA_SERVERBOUND_PAYLOAD_BYTES = 32767;
    public static final int VANILLA_CLIENTBOUND_PAYLOAD_BYTES = 1024 * 1024;
    /**
     * Two identifiers of at most 64 UTF-8 bytes each with their VarInt prefixes (Architectury's
     * {@code architectury:network} plus {@code quickskin:<packet>}, or a loader's single channel).
     */
    public static final int ENVELOPE_BYTES = 2 * (64 + 1);
    private static final int UUID_BYTES = 16;
    private static final int INT_BYTES = 4;

    private WirePayloadBudget() {
    }

    public static int varIntBytes(int value) {
        int bytes = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            bytes++;
        }
        return bytes;
    }

    static int string(int maximumBytes) {
        return varIntBytes(maximumBytes) + maximumBytes;
    }

    /** The larger of the two byte-array layouts (VarInt before 1.21, int from 1.21). */
    static int byteArray(int maximumBytes) {
        return Math.max(varIntBytes(maximumBytes), INT_BYTES) + maximumBytes;
    }

    /** {@code texture_chunk[_v2]}: content id, type, index, total and one upload chunk. */
    public static int textureUploadChunk(int chunkBytes) {
        return ENVELOPE_BYTES + string(TextureTransferLimits.MAX_CONTENT_ID_BYTES)
                + string(TextureTransferLimits.MAX_TEXTURE_TYPE_BYTES)
                + 2 * INT_BYTES + byteArray(chunkBytes);
    }

    /** {@code send_texture_chunk[_v2]}: the same layout, served by the server. */
    public static int textureResponseChunk(int chunkBytes) {
        return textureUploadChunk(chunkBytes);
    }

    /** {@code send_texture[_v2]}: type, content id and a whole texture. */
    public static int directTexture(int textureBytes) {
        return ENVELOPE_BYTES + string(TextureTransferLimits.MAX_TEXTURE_TYPE_BYTES)
                + string(TextureTransferLimits.MAX_CONTENT_ID_BYTES) + byteArray(textureBytes);
    }

    /** {@code [upload|send]_animation_metadata[_v2]}: content id and one metadata document. */
    public static int animationMetadata() {
        return ENVELOPE_BYTES + string(TextureTransferLimits.MAX_CONTENT_ID_BYTES)
                + string(TextureTransferLimits.MAX_ANIMATION_METADATA_JSON_BYTES);
    }

    /** {@code [update|sync]_appearance[_v2]}: player, skin id, cape id and model. */
    public static int appearance() {
        return ENVELOPE_BYTES + UUID_BYTES
                + 2 * string(TextureTransferLimits.MAX_APPEARANCE_ID_BYTES)
                + string(TextureTransferLimits.MAX_MODEL_BYTES);
    }

    /** {@code sync_server_config}: one configuration document. */
    public static int serverConfig() {
        return ENVELOPE_BYTES + string(TextureTransferLimits.MAX_JSON_BYTES);
    }
}
