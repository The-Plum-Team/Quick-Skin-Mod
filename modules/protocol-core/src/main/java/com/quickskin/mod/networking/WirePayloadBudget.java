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
    private static final int LONG_BYTES = 8;

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

    /**
     * {@code [upload|send]_animation_metadata[_v2]}: content id and one metadata document, which
     * every codec writes within {@link TextureTransferLimits#MAX_ANIMATION_METADATA_JSON_BYTES}.
     */
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

    /** {@code set_nickname}: player, request number and the nickname. */
    public static int setNickname() {
        return ENVELOPE_BYTES + UUID_BYTES + INT_BYTES
                + string(TextureTransferLimits.MAX_NICKNAME_BYTES);
    }

    /** {@code armor_visibility}: player and the armour pieces it hides. */
    public static int armorVisibility() {
        return ENVELOPE_BYTES + UUID_BYTES + 1;
    }

    /** {@code nickname_result}: request number, status, the nickname kept and the wait. */
    public static int nicknameResult() {
        return ENVELOPE_BYTES + INT_BYTES + 1
                + string(TextureTransferLimits.MAX_NICKNAME_BYTES) + LONG_BYTES;
    }

    /** {@code assigned_skin}: the skin id and model the server gave the player ({@code ""}: none). */
    public static int assignedSkin() {
        return ENVELOPE_BYTES + string(TextureTransferLimits.MAX_APPEARANCE_ID_BYTES)
                + string(TextureTransferLimits.MAX_MODEL_BYTES);
    }

    /**
     * {@code skin_admin}: request number, action, a count and up to
     * {@code MAX_SKIN_ADMIN_PLAYERS} players, a skin id, a model and a pool skin's name (counted
     * as three bytes per character, as the 1.20.1 codec may).
     */
    public static int skinAdmin() {
        return ENVELOPE_BYTES + INT_BYTES + 1 + INT_BYTES
                + TextureTransferLimits.MAX_SKIN_ADMIN_PLAYERS * UUID_BYTES
                + string(TextureTransferLimits.MAX_APPEARANCE_ID_BYTES)
                + string(TextureTransferLimits.MAX_MODEL_BYTES)
                + string(3 * TextureTransferLimits.MAX_POOL_SKIN_NAME_CHARS);
    }

    /** {@code skin_admin_result}: request number, status and how many players or skins it touched. */
    public static int skinAdminResult() {
        return ENVELOPE_BYTES + INT_BYTES + 1 + INT_BYTES;
    }

    /** {@code skin_admin_state}: the pool's skins, then the players who have an assigned skin. */
    public static int skinAdminState() {
        return ENVELOPE_BYTES + INT_BYTES + TextureTransferLimits.MAX_POOL_SKINS
                * (string(3 * TextureTransferLimits.MAX_POOL_SKIN_NAME_CHARS)
                        + string(TextureTransferLimits.MAX_APPEARANCE_ID_BYTES)
                        + string(TextureTransferLimits.MAX_MODEL_BYTES))
                + INT_BYTES + TextureTransferLimits.MAX_ASSIGNED_SKIN_ENTRIES * UUID_BYTES;
    }

    /** {@code sync_nicknames}: a count and up to {@code MAX_NICKNAME_ENTRIES} players' nicknames. */
    public static int syncNicknames() {
        return ENVELOPE_BYTES + INT_BYTES + TextureTransferLimits.MAX_NICKNAME_ENTRIES
                * (UUID_BYTES + string(TextureTransferLimits.MAX_NICKNAME_BYTES));
    }
}
