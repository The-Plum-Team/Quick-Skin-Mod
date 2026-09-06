package com.quickskin.mod.networking.protocol;

import com.quickskin.mod.networking.TextureTransferLimits;

/** One authoritative local protocol policy for clients and servers. */
public final class QuickSkinProtocol {
    public static final long CAPABILITIES = ProtocolCapability.knownMask();
    public static final long REQUIRED_CAPABILITIES =
            ProtocolCapability.SHA256_CONTENT_IDS.mask()
                    | ProtocolCapability.CHUNKED_TEXTURE_TRANSFER.mask();
    public static final ProtocolNegotiator.Policy POLICY = new ProtocolNegotiator.Policy(
            ProtocolNegotiator.CURRENT_VERSION,
            ProtocolNegotiator.CURRENT_VERSION,
            CAPABILITIES,
            REQUIRED_CAPABILITIES,
            TextureTransferLimits.MAX_TEXTURE_BYTES,
            TextureTransferLimits.MAX_WIRE_CHUNK_BYTES);

    private QuickSkinProtocol() {
    }
}
