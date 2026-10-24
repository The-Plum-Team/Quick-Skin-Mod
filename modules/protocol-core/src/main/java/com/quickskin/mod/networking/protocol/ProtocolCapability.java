package com.quickskin.mod.networking.protocol;

/** Stable application-level capabilities negotiated independently from loader channel presence. */
public enum ProtocolCapability {
    SHA256_CONTENT_IDS("sha256-content-ids", 1L << 0),
    CHUNKED_TEXTURE_TRANSFER("chunked-texture-transfer", 1L << 1),
    ANIMATION_METADATA("animation-metadata", 1L << 2),
    APPEARANCE_SNAPSHOT_ACK("appearance-snapshot-ack", 1L << 3),
    /**
     * The client may report that it changed its own Mojang account skin, so a server that shares
     * account skins with players without Quick Skin can refresh the signed vanilla profile.
     */
    ACCOUNT_SKIN_REFRESH("account-skin-refresh", 1L << 4),
    /**
     * The client may tell the server its cosmetic nickname, and the server sends the nicknames of
     * the players online so the client can show them on name tags.
     */
    NICKNAMES("nicknames", 1L << 5),
    /**
     * The server may give a player a skin for now, which others see instead of theirs, and tells
     * that player's client so it shows it too; an admin's client may hand out such skins and keep
     * the server's skin pool.
     */
    ASSIGNED_SKINS("assigned-skins", 1L << 6),
    /**
     * The client may tell the server which of its armour pieces to hide, and the server leaves
     * them out of the equipment it sends every other player, Quick Skin or not.
     */
    HIDDEN_ARMOR("hidden-armor", 1L << 7);

    private final String id;
    private final long mask;

    ProtocolCapability(String id, long mask) {
        this.id = id;
        this.mask = mask;
    }

    public String id() {
        return id;
    }

    public long mask() {
        return mask;
    }

    public static long knownMask() {
        long result = 0L;
        for (ProtocolCapability capability : values()) {
            result |= capability.mask;
        }
        return result;
    }
}
