package com.quickskin.mod.networking.protocol;

import com.quickskin.mod.networking.TextureTransferLimits;

/** Immutable per-connection result of application-level protocol negotiation. */
public record ProtocolProfile(
        Mode mode,
        int version,
        long capabilityMask,
        int maximumTextureBytes,
        int maximumChunkBytes,
        String reason
) {
    public static ProtocolProfile localOnly(String reason) {
        return new ProtocolProfile(Mode.LOCAL_ONLY, 0, 0L, 0, 0, reason);
    }

    public static ProtocolProfile legacy(String reason) {
        return legacy(reason, QuickSkinProtocol.POLICY);
    }

    /** A legacy session bounded by the given local policy (a server's configured upload limit). */
    public static ProtocolProfile legacy(String reason, ProtocolNegotiator.Policy policy) {
        return new ProtocolProfile(
                Mode.LEGACY_V1,
                1,
                0L,
                policy.maximumTextureBytes(),
                Math.min(policy.maximumChunkBytes(), policy.maximumTextureBytes()),
                reason);
    }

    public static ProtocolProfile incompatible(String reason) {
        return new ProtocolProfile(Mode.INCOMPATIBLE, 0, 0L, 0, 0, reason);
    }

    public boolean negotiated() {
        return mode == Mode.NEGOTIATED;
    }

    public boolean supports(ProtocolCapability capability) {
        return capability != null && (capabilityMask & capability.mask()) != 0L;
    }

    /**
     * Upload bound for the local client. A legacy v1 server relays each upload unchunked; servers
     * keep using {@link #maximumTextureBytes()} for what they accept and serve in chunks.
     */
    public int maximumUploadBytes() {
        return mode == Mode.LEGACY_V1
                ? Math.min(maximumTextureBytes, TextureTransferLimits.MAX_LEGACY_UPLOAD_BYTES)
                : maximumTextureBytes;
    }

    public enum Mode {
        LOCAL_ONLY,
        LEGACY_V1,
        NEGOTIATED,
        INCOMPATIBLE
    }
}
