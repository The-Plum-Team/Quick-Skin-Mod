package com.quickskin.mod.networking.protocol;

import com.quickskin.mod.networking.TextureTransferLimits;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolNegotiatorTest {
    private static final long REQUIRED = ProtocolCapability.SHA256_CONTENT_IDS.mask();
    private static final long LOCAL_CAPABILITIES = REQUIRED
            | ProtocolCapability.CHUNKED_TEXTURE_TRANSFER.mask()
            | ProtocolCapability.ANIMATION_METADATA.mask()
            | ProtocolCapability.APPEARANCE_SNAPSHOT_ACK.mask();
    private static final ProtocolNegotiator.Policy LOCAL = new ProtocolNegotiator.Policy(
            2, 2, LOCAL_CAPABILITIES, REQUIRED, 16 * 1024 * 1024, 30 * 1024);

    @Test
    void negotiatesOnlyTheIntersectionAndTheSmallerLimits() {
        ProtocolOffer peer = new ProtocolOffer(
                2,
                3,
                REQUIRED | ProtocolCapability.CHUNKED_TEXTURE_TRANSFER.mask() | (1L << 40),
                8 * 1024 * 1024,
                64 * 1024);

        ProtocolProfile profile = ProtocolNegotiator.negotiate(LOCAL, peer);

        assertTrue(profile.negotiated());
        assertEquals(2, profile.version());
        assertTrue(profile.supports(ProtocolCapability.SHA256_CONTENT_IDS));
        assertTrue(profile.supports(ProtocolCapability.CHUNKED_TEXTURE_TRANSFER));
        assertFalse(profile.supports(ProtocolCapability.ANIMATION_METADATA));
        assertEquals(8 * 1024 * 1024, profile.maximumTextureBytes());
        assertEquals(30 * 1024, profile.maximumChunkBytes());
    }

    @Test
    void rejectsMissingCapabilitiesAndDisjointVersions() {
        ProtocolProfile missingRequired = ProtocolNegotiator.negotiate(
                LOCAL,
                new ProtocolOffer(2, 2, 0L, 1024, 512));
        ProtocolProfile oldPeer = ProtocolNegotiator.negotiate(
                LOCAL,
                new ProtocolOffer(1, 1, LOCAL_CAPABILITIES, 1024, 512));

        assertEquals(ProtocolProfile.Mode.INCOMPATIBLE, missingRequired.mode());
        assertEquals("missing-required-capability", missingRequired.reason());
        assertEquals(ProtocolProfile.Mode.INCOMPATIBLE, oldPeer.mode());
        assertEquals("no-common-version", oldPeer.reason());
    }

    @Test
    void rejectsMalformedPeerLimitsInsteadOfRaisingLocalBounds() {
        ProtocolProfile malformed = ProtocolNegotiator.negotiate(
                LOCAL,
                new ProtocolOffer(2, 2, LOCAL_CAPABILITIES, 1024, 2048));

        assertEquals(ProtocolProfile.Mode.INCOMPATIBLE, malformed.mode());
        assertEquals("malformed-offer", malformed.reason());
    }

    @Test
    void validatesTheLocalPolicyAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProtocolNegotiator.Policy(2, 2, 0L, REQUIRED, 1024, 512));
        assertThrows(IllegalArgumentException.class,
                () -> new ProtocolNegotiator.Policy(2, 2, LOCAL_CAPABILITIES, REQUIRED, 512, 1024));
    }

    @Test
    void acknowledgementCannotRaiseLocalLimitsOrInventCapabilities() {
        ProtocolProfile raisedLimit = ProtocolNegotiator.verifyAcknowledgement(
                LOCAL,
                new ProtocolAcknowledgement(
                        true, 2, LOCAL_CAPABILITIES,
                        32 * 1024 * 1024, 30 * 1024));
        ProtocolProfile inventedCapability = ProtocolNegotiator.verifyAcknowledgement(
                LOCAL,
                new ProtocolAcknowledgement(
                        true, 2, LOCAL_CAPABILITIES | (1L << 50),
                        1024, 512));

        assertEquals(ProtocolProfile.Mode.INCOMPATIBLE, raisedLimit.mode());
        assertEquals(ProtocolProfile.Mode.INCOMPATIBLE, inventedCapability.mode());
    }

    @Test
    void negotiatedTextureLimitIsRepresentableByTheBoundedChunkCount() {
        int smallChunkLimit = 1024;
        ProtocolProfile profile = ProtocolNegotiator.negotiate(
                LOCAL,
                new ProtocolOffer(
                        2, 2, LOCAL_CAPABILITIES,
                        TextureTransferLimits.MAX_TEXTURE_BYTES,
                        smallChunkLimit));

        assertTrue(profile.negotiated());
        assertEquals(smallChunkLimit, profile.maximumChunkBytes());
        assertEquals(
                smallChunkLimit * TextureTransferLimits.MAX_CHUNKS,
                profile.maximumTextureBytes());
        assertTrue((long) profile.maximumTextureBytes()
                <= (long) profile.maximumChunkBytes() * TextureTransferLimits.MAX_CHUNKS);

        ProtocolAcknowledgement acknowledgement =
                ProtocolAcknowledgement.accepted(profile);
        assertEquals(
                profile,
                ProtocolNegotiator.verifyAcknowledgement(LOCAL, acknowledgement));
    }
}
