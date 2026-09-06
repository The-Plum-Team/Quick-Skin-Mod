package com.quickskin.mod.networking.protocol;

/** Bounded server response to one exact hello nonce. */
public record ProtocolAcknowledgement(
        boolean accepted,
        int selectedVersion,
        long capabilityMask,
        int maximumTextureBytes,
        int maximumChunkBytes
) {
    public static ProtocolAcknowledgement accepted(ProtocolProfile profile) {
        if (profile == null || !profile.negotiated()) {
            throw new IllegalArgumentException("A negotiated profile is required");
        }
        return new ProtocolAcknowledgement(
                true,
                profile.version(),
                profile.capabilityMask(),
                profile.maximumTextureBytes(),
                profile.maximumChunkBytes());
    }

    public static ProtocolAcknowledgement rejected() {
        return new ProtocolAcknowledgement(false, 0, 0L, 0, 0);
    }
}
