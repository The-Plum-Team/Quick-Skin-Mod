package com.quickskin.mod.networking;

import com.quickskin.mod.networking.protocol.ProtocolCapability;
import com.quickskin.mod.networking.protocol.ProtocolProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Only a session that negotiated {@code assigned-skins} may send a skin request at all. */
class ServerSkinAssignmentsAdmissionTest {
    private static final long V2 = ProtocolCapability.SHA256_CONTENT_IDS.mask()
            | ProtocolCapability.CHUNKED_TEXTURE_TRANSFER.mask();

    @Test
    void aNegotiatedSessionWithTheCapabilityIsAdmitted() {
        assertTrue(ServerSkinAssignments.admits(negotiated(V2 | assignedSkins())));
    }

    @Test
    void theSessionMustHaveNegotiatedTheCapability() {
        assertFalse(ServerSkinAssignments.admits(negotiated(V2)));
        assertFalse(ServerSkinAssignments.admits(negotiated(assignedSkins())), "a request needs a v2 session");
        assertFalse(ServerSkinAssignments.admits(ProtocolProfile.legacy("v1")));
        assertFalse(ServerSkinAssignments.admits(ProtocolProfile.localOnly("vanilla")));
        assertFalse(ServerSkinAssignments.admits((ProtocolProfile) null));
    }

    private static long assignedSkins() {
        return ProtocolCapability.ASSIGNED_SKINS.mask();
    }

    private static ProtocolProfile negotiated(long capabilities) {
        return new ProtocolProfile(ProtocolProfile.Mode.NEGOTIATED, 2, capabilities, 1, 1, "test");
    }
}
