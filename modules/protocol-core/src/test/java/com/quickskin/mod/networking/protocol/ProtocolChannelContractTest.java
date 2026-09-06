package com.quickskin.mod.networking.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolChannelContractTest {
    private static ProtocolProfile profile(long capabilities) {
        return new ProtocolProfile(
                ProtocolProfile.Mode.NEGOTIATED,
                ProtocolNegotiator.CURRENT_VERSION,
                capabilities,
                1024,
                256,
                "test");
    }

    @Test
    void negotiatedV2GuaranteesCoreChannelsWithoutLoaderProbe() {
        ProtocolProfile profile = profile(ProtocolCapability.SHA256_CONTENT_IDS.mask()
                | ProtocolCapability.CHUNKED_TEXTURE_TRANSFER.mask());

        assertTrue(ProtocolChannelContract.guarantees(
                profile, ProtocolChannelContract.ServerPacket.APPEARANCE));
        assertTrue(ProtocolChannelContract.guarantees(
                profile, ProtocolChannelContract.ServerPacket.TEXTURE));
        assertTrue(ProtocolChannelContract.guarantees(
                profile, ProtocolChannelContract.ServerPacket.TEXTURE_CHUNK));
        assertTrue(ProtocolChannelContract.guarantees(
                profile, ProtocolChannelContract.ServerPacket.SERVER_CONFIG));
        assertTrue(ProtocolChannelContract.guarantees(
                profile, ProtocolChannelContract.ServerPacket.COOLDOWN));
    }

    @Test
    void optionalChannelsRequireTheirNegotiatedCapability() {
        ProtocolProfile core = profile(ProtocolCapability.SHA256_CONTENT_IDS.mask()
                | ProtocolCapability.CHUNKED_TEXTURE_TRANSFER.mask());
        ProtocolProfile all = profile(ProtocolCapability.knownMask());

        assertFalse(ProtocolChannelContract.guarantees(
                core, ProtocolChannelContract.ServerPacket.ANIMATION_METADATA));
        assertFalse(ProtocolChannelContract.guarantees(
                core, ProtocolChannelContract.ServerPacket.APPEARANCE_SNAPSHOT_COMPLETE));
        assertTrue(ProtocolChannelContract.guarantees(
                all, ProtocolChannelContract.ServerPacket.ANIMATION_METADATA));
        assertTrue(ProtocolChannelContract.guarantees(
                all, ProtocolChannelContract.ServerPacket.APPEARANCE_SNAPSHOT_COMPLETE));
    }

    @Test
    void classifiedLegacyProfileGuaranteesTheImmutableV1ReceiverFamily() {
        assertTrue(ProtocolChannelContract.guarantees(
                ProtocolProfile.legacy("legacy"),
                ProtocolChannelContract.ServerPacket.APPEARANCE));
        assertTrue(ProtocolChannelContract.guarantees(
                ProtocolProfile.legacy("legacy"),
                ProtocolChannelContract.ServerPacket.TEXTURE_CHUNK));
        assertTrue(ProtocolChannelContract.guarantees(
                ProtocolProfile.legacy("legacy"),
                ProtocolChannelContract.ServerPacket.APPEARANCE_SNAPSHOT_COMPLETE));
    }

    @Test
    void unclassifiedProfilesNeverBypassLoaderProbe() {
        assertFalse(ProtocolChannelContract.guarantees(
                ProtocolProfile.localOnly("pending"),
                ProtocolChannelContract.ServerPacket.APPEARANCE));
        assertFalse(ProtocolChannelContract.guarantees(
                null, ProtocolChannelContract.ServerPacket.APPEARANCE));
    }

    @Test
    void internallyMalformedNegotiatedProfileCannotAuthorizeChannels() {
        ProtocolProfile missingRequiredCapability = profile(
                ProtocolCapability.SHA256_CONTENT_IDS.mask());

        assertFalse(ProtocolChannelContract.guarantees(
                missingRequiredCapability,
                ProtocolChannelContract.ServerPacket.APPEARANCE));
        assertFalse(ProtocolChannelContract.guarantees(
                missingRequiredCapability,
                ProtocolChannelContract.ServerPacket.TEXTURE));
    }
}
