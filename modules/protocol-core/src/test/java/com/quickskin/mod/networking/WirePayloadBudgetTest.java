package com.quickskin.mod.networking;

import com.quickskin.mod.networking.protocol.ProtocolNegotiator;
import com.quickskin.mod.networking.protocol.ProtocolProfile;
import com.quickskin.mod.networking.protocol.QuickSkinProtocol;
import org.junit.jupiter.api.Test;

import static com.quickskin.mod.networking.WirePayloadBudget.VANILLA_CLIENTBOUND_PAYLOAD_BYTES;
import static com.quickskin.mod.networking.WirePayloadBudget.VANILLA_SERVERBOUND_PAYLOAD_BYTES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every packet Quick Skin sends fits the vanilla limits, whatever the size of the asset. */
class WirePayloadBudgetTest {
    /** A frame of 2 MiB or more needs a 4-byte length prefix; vanilla frames allow three. */
    private static final int MAX_VANILLA_FRAME_BYTES = (1 << 21) - 1;

    @Test
    void varIntWidthsMatchTheVanillaEncoding() {
        assertEquals(1, WirePayloadBudget.varIntBytes(0));
        assertEquals(1, WirePayloadBudget.varIntBytes(127));
        assertEquals(2, WirePayloadBudget.varIntBytes(128));
        assertEquals(3, WirePayloadBudget.varIntBytes(MAX_VANILLA_FRAME_BYTES));
        assertEquals(4, WirePayloadBudget.varIntBytes(MAX_VANILLA_FRAME_BYTES + 1));
        assertEquals(5, WirePayloadBudget.varIntBytes(-1));
    }

    @Test
    void everyUploadChunkFitsOneServerboundPayload() {
        // The client never sends a chunk above CHUNK_BYTES, whatever the peer advertised.
        assertTrue(WirePayloadBudget.textureUploadChunk(TextureTransferLimits.CHUNK_BYTES)
                <= VANILLA_SERVERBOUND_PAYLOAD_BYTES);
        int chunkSize = Math.min(TextureTransferLimits.CHUNK_BYTES,
                QuickSkinProtocol.POLICY.maximumChunkBytes());
        assertTrue(WirePayloadBudget.textureUploadChunk(chunkSize)
                <= VANILLA_SERVERBOUND_PAYLOAD_BYTES);
    }

    @Test
    void everyServedTexturePacketFitsOneClientboundPayload() {
        assertTrue(WirePayloadBudget.textureResponseChunk(TextureTransferLimits.MAX_WIRE_CHUNK_BYTES)
                <= VANILLA_CLIENTBOUND_PAYLOAD_BYTES);
        assertTrue(WirePayloadBudget.directTexture(TextureTransferLimits.MAX_DIRECT_TEXTURE_BYTES)
                <= VANILLA_CLIENTBOUND_PAYLOAD_BYTES);
    }

    @Test
    void theLargestTextureStillNeedsOnlyBoundedChunks() {
        int chunks = (TextureTransferLimits.MAX_TEXTURE_BYTES + TextureTransferLimits.CHUNK_BYTES - 1)
                / TextureTransferLimits.CHUNK_BYTES;
        assertTrue(chunks <= TextureTransferLimits.MAX_CHUNKS);
        ProtocolProfile negotiated = ProtocolNegotiator.negotiate(
                QuickSkinProtocol.POLICY, QuickSkinProtocol.POLICY.offer());
        assertTrue((long) negotiated.maximumChunkBytes() * TextureTransferLimits.MAX_CHUNKS
                >= negotiated.maximumTextureBytes());
    }

    @Test
    void animationMetadataFitsInBothDirections() {
        assertTrue(WirePayloadBudget.animationMetadata() <= VANILLA_SERVERBOUND_PAYLOAD_BYTES);
        assertTrue(TextureTransferLimits.MAX_ANIMATION_METADATA_JSON_BYTES
                <= TextureTransferLimits.MAX_JSON_BYTES);
    }

    @Test
    void controlPacketsFitTheirDirection() {
        assertTrue(WirePayloadBudget.appearance() <= VANILLA_SERVERBOUND_PAYLOAD_BYTES);
        assertTrue(WirePayloadBudget.serverConfig() <= VANILLA_CLIENTBOUND_PAYLOAD_BYTES);
    }

    @Test
    void aLegacyServerRelayOfTheLargestAllowedUploadFitsOneClientboundPayload() {
        // A 2.x server relays each stored texture whole as quickskin:send_texture.
        assertTrue(WirePayloadBudget.directTexture(TextureTransferLimits.MAX_LEGACY_UPLOAD_BYTES)
                <= VANILLA_CLIENTBOUND_PAYLOAD_BYTES);
        // Whatever vanilla delivers in one clientbound payload is decoded by a 3.x client.
        assertEquals(VANILLA_CLIENTBOUND_PAYLOAD_BYTES,
                TextureTransferLimits.MAX_LEGACY_DIRECT_TEXTURE_BYTES);
        assertTrue(TextureTransferLimits.MAX_LEGACY_DIRECT_TEXTURE_BYTES
                >= TextureTransferLimits.MAX_LEGACY_UPLOAD_BYTES);
        assertTrue(VANILLA_CLIENTBOUND_PAYLOAD_BYTES < MAX_VANILLA_FRAME_BYTES);
    }

    @Test
    void serverUploadLimitsStayInsideTheTransportBounds() {
        assertEquals(TextureTransferLimits.MIN_SERVER_UPLOAD_BYTES,
                TextureTransferLimits.clampServerUploadBytes(Long.MIN_VALUE));
        assertEquals(TextureTransferLimits.MAX_TEXTURE_BYTES,
                TextureTransferLimits.clampServerUploadBytes(Long.MAX_VALUE));
        assertEquals(1_000_000, TextureTransferLimits.clampServerUploadBytes(1_000_000));
        assertTrue(TextureTransferLimits.MIN_SERVER_UPLOAD_BYTES
                >= TextureTransferLimits.MAX_WIRE_CHUNK_BYTES);
        assertTrue(TextureTransferLimits.DEFAULT_SERVER_UPLOAD_BYTES
                <= TextureTransferLimits.MAX_TEXTURE_BYTES);
    }

    @Test
    void sizesAreDescribedForTheRejectionMessage() {
        assertEquals("0 KiB", TextureTransferLimits.describeBytes(0));
        assertEquals("1 KiB", TextureTransferLimits.describeBytes(1));
        assertEquals("1023 KiB", TextureTransferLimits.describeBytes(TextureTransferLimits.MAX_LEGACY_UPLOAD_BYTES));
        assertEquals("1.0 MiB", TextureTransferLimits.describeBytes(1024 * 1024));
        assertEquals("16.0 MiB", TextureTransferLimits.describeBytes(TextureTransferLimits.MAX_TEXTURE_BYTES));
        assertEquals("12.5 MiB", TextureTransferLimits.describeBytes(12L * 1024 * 1024 + 512 * 1024));
    }
}
