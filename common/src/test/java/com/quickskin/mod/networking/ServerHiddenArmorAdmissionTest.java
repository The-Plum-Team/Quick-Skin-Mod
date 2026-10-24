package com.quickskin.mod.networking;

import com.quickskin.mod.networking.protocol.ProtocolCapability;
import com.quickskin.mod.networking.protocol.ProtocolProfile;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Only a session that negotiated {@code hidden-armor} may hide armour, and only its own. */
class ServerHiddenArmorAdmissionTest {
    private static final UUID SENDER = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");
    private static final UUID OTHER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final long V2 = ProtocolCapability.SHA256_CONTENT_IDS.mask()
            | ProtocolCapability.CHUNKED_TEXTURE_TRANSFER.mask();

    @Test
    void aNegotiatedSessionMayHideItsOwnArmour() {
        assertTrue(ServerHiddenArmor.admits(negotiated(V2 | hiddenArmor()), SENDER, SENDER));
    }

    @Test
    void theNamedPlayerMustBeTheSender() {
        ProtocolProfile profile = negotiated(V2 | hiddenArmor());

        assertFalse(ServerHiddenArmor.admits(profile, SENDER, OTHER),
                "a client can never hide another player's armour");
        assertFalse(ServerHiddenArmor.admits(profile, SENDER, null));
        assertFalse(ServerHiddenArmor.admits(profile, null, SENDER));
    }

    @Test
    void theSessionMustHaveNegotiatedTheCapability() {
        assertFalse(ServerHiddenArmor.admits(negotiated(V2), SENDER, SENDER));
        assertFalse(ServerHiddenArmor.admits(negotiated(hiddenArmor()), SENDER, SENDER),
                "hiding armour needs a v2 session");
        assertFalse(ServerHiddenArmor.admits(ProtocolProfile.legacy("v1"), SENDER, SENDER));
        assertFalse(ServerHiddenArmor.admits(ProtocolProfile.localOnly("vanilla"), SENDER, SENDER));
        assertFalse(ServerHiddenArmor.admits(null, SENDER, SENDER));
    }

    private static long hiddenArmor() {
        return ProtocolCapability.HIDDEN_ARMOR.mask();
    }

    private static ProtocolProfile negotiated(long capabilities) {
        return new ProtocolProfile(ProtocolProfile.Mode.NEGOTIATED, 2, capabilities, 1, 1, "test");
    }
}
