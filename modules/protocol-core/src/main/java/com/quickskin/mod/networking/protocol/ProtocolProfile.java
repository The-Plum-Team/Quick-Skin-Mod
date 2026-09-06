package com.quickskin.mod.networking.protocol;

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
        return new ProtocolProfile(
                Mode.LEGACY_V1,
                1,
                0L,
                QuickSkinProtocol.POLICY.maximumTextureBytes(),
                QuickSkinProtocol.POLICY.maximumChunkBytes(),
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

    public enum Mode {
        LOCAL_ONLY,
        LEGACY_V1,
        NEGOTIATED,
        INCOMPATIBLE
    }
}
