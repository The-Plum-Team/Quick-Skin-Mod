package com.quickskin.mod.networking.protocol;

/** Untrusted protocol limits and features advertised by one peer during the hello exchange. */
public record ProtocolOffer(
        int minimumVersion,
        int maximumVersion,
        long capabilityMask,
        int maximumTextureBytes,
        int maximumChunkBytes
) {
    public boolean isStructurallyValid() {
        return minimumVersion > 0
                && maximumVersion >= minimumVersion
                && maximumTextureBytes > 0
                && maximumChunkBytes > 0
                && maximumChunkBytes <= maximumTextureBytes;
    }
}
