package com.quickskin.mod.networking.protocol;

/** Stable application-level capabilities negotiated independently from loader channel presence. */
public enum ProtocolCapability {
    SHA256_CONTENT_IDS("sha256-content-ids", 1L << 0),
    CHUNKED_TEXTURE_TRANSFER("chunked-texture-transfer", 1L << 1),
    ANIMATION_METADATA("animation-metadata", 1L << 2),
    APPEARANCE_SNAPSHOT_ACK("appearance-snapshot-ack", 1L << 3);

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
