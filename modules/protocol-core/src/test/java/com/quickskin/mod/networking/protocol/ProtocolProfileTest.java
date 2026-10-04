package com.quickskin.mod.networking.protocol;

import com.quickskin.mod.networking.TextureTransferLimits;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolProfileTest {
    @Test
    void legacyServerCapsOnlyWhatTheClientUploads() {
        ProtocolProfile legacy = ProtocolProfile.legacy("legacy channels");

        assertEquals(TextureTransferLimits.MAX_LEGACY_UPLOAD_BYTES, legacy.maximumUploadBytes());
        // A 3.x server serves 2.x clients in chunks under this same profile.
        assertEquals(TextureTransferLimits.MAX_TEXTURE_BYTES, legacy.maximumTextureBytes());
    }

    @Test
    void negotiatedSessionUploadsUpToTheNegotiatedTextureLimit() {
        ProtocolProfile negotiated = ProtocolNegotiator.negotiate(
                QuickSkinProtocol.POLICY, QuickSkinProtocol.POLICY.offer());

        assertTrue(negotiated.negotiated());
        assertEquals(negotiated.maximumTextureBytes(), negotiated.maximumUploadBytes());
        assertEquals(TextureTransferLimits.MAX_TEXTURE_BYTES, negotiated.maximumUploadBytes());
    }

    @Test
    void legacyUploadFitsOneUnchunkedClientboundPayload() {
        // Packet id, channel, texture type, 40-character hash and the length prefix.
        int relayHeaderBytes = 128;

        assertTrue(TextureTransferLimits.MAX_LEGACY_UPLOAD_BYTES + relayHeaderBytes <= 1024 * 1024);
    }
}
