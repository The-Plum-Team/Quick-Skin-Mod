package com.quickskin.mod.networking;

import com.quickskin.mod.common.data.RemoteImmersivePoses;
import com.quickskin.mod.networking.protocol.ProtocolCapability;
import com.quickskin.mod.networking.protocol.ProtocolProfile;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Only a session that negotiated {@code immersive-poses} may tell a pose, and only its own player's. */
class ServerImmersivePosesAdmissionTest {
    private static final UUID SENDER = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");
    private static final UUID OTHER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final long V2 = ProtocolCapability.SHA256_CONTENT_IDS.mask()
            | ProtocolCapability.CHUNKED_TEXTURE_TRANSFER.mask();

    @Test
    void aNegotiatedSessionMayTellItsOwnPose() {
        assertTrue(ServerImmersivePoses.admits(negotiated(V2 | immersivePoses()), SENDER, SENDER));
    }

    @Test
    void theNamedPlayerMustBeTheSender() {
        ProtocolProfile profile = negotiated(V2 | immersivePoses());

        assertFalse(ServerImmersivePoses.admits(profile, SENDER, OTHER),
                "a client can never pose another player");
        assertFalse(ServerImmersivePoses.admits(profile, SENDER, null));
        assertFalse(ServerImmersivePoses.admits(profile, null, SENDER));
    }

    @Test
    void theSessionMustHaveNegotiatedTheCapability() {
        assertFalse(ServerImmersivePoses.admits(negotiated(V2), SENDER, SENDER));
        assertFalse(ServerImmersivePoses.admits(negotiated(V2 | ProtocolCapability.HIDDEN_ARMOR.mask()), SENDER, SENDER));
        assertFalse(ServerImmersivePoses.admits(negotiated(immersivePoses()), SENDER, SENDER),
                "posing needs a v2 session");
        assertFalse(ServerImmersivePoses.admits(ProtocolProfile.legacy("v1"), SENDER, SENDER));
        assertFalse(ServerImmersivePoses.admits(ProtocolProfile.localOnly("vanilla"), SENDER, SENDER));
        assertFalse(ServerImmersivePoses.admits(null, SENDER, SENDER));
    }

    @Test
    void theClientsCopyOfThePoseNumbersMatchesTheWire() {
        // client-foundation cannot see protocol-core, so it keeps its own copy of the numbers
        assertEquals(ImmersivePose.NONE, RemoteImmersivePoses.NONE);
        assertEquals(ImmersivePose.IDLE, RemoteImmersivePoses.IDLE);
        assertEquals(ImmersivePose.WALK, RemoteImmersivePoses.WALK);
        assertEquals(ImmersivePose.SIT, RemoteImmersivePoses.SIT);
        assertEquals(ImmersivePose.CLOSING, RemoteImmersivePoses.CLOSING);
        assertEquals(ImmersivePose.SETTLED, RemoteImmersivePoses.SETTLED);
        for (int pose = ImmersivePose.IDLE; pose <= ImmersivePose.MAX_POSE; pose++) {
            assertEquals(ImmersivePose.animationOf(pose), RemoteImmersivePoses.animationOf(pose));
        }
    }

    private static long immersivePoses() {
        return ProtocolCapability.IMMERSIVE_POSES.mask();
    }

    private static ProtocolProfile negotiated(long capabilities) {
        return new ProtocolProfile(ProtocolProfile.Mode.NEGOTIATED, 2, capabilities, 1, 1, "test");
    }
}
