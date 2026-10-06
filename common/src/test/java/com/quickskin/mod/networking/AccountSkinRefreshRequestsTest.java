package com.quickskin.mod.networking;

import com.quickskin.mod.networking.protocol.ProtocolCapability;
import com.quickskin.mod.networking.protocol.ProtocolProfile;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountSkinRefreshRequestsTest {
    private static final UUID SENDER = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");
    private static final UUID OTHER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final long V2 = ProtocolCapability.SHA256_CONTENT_IDS.mask()
            | ProtocolCapability.CHUNKED_TEXTURE_TRANSFER.mask();

    @Test
    void aNegotiatedSessionMayReportItsOwnAccountSkin() {
        assertTrue(AccountSkinRefreshRequests.admits(negotiated(V2 | refresh()), SENDER, SENDER));
    }

    @Test
    void theReportedPlayerMustBeTheSender() {
        ProtocolProfile profile = negotiated(V2 | refresh());

        assertFalse(AccountSkinRefreshRequests.admits(profile, SENDER, OTHER),
                "a client can never trigger a refresh of another player");
        assertFalse(AccountSkinRefreshRequests.admits(profile, SENDER, null));
        assertFalse(AccountSkinRefreshRequests.admits(profile, null, SENDER));
    }

    @Test
    void theSessionMustHaveNegotiatedTheCapability() {
        assertFalse(AccountSkinRefreshRequests.admits(negotiated(V2), SENDER, SENDER));
        assertFalse(AccountSkinRefreshRequests.admits(negotiated(refresh()), SENDER, SENDER),
                "the report needs a v2 session");
        assertFalse(AccountSkinRefreshRequests.admits(ProtocolProfile.legacy("v1"), SENDER, SENDER));
        assertFalse(AccountSkinRefreshRequests.admits(ProtocolProfile.localOnly("vanilla"), SENDER, SENDER));
        assertFalse(AccountSkinRefreshRequests.admits(null, SENDER, SENDER));
    }

    private static long refresh() {
        return ProtocolCapability.ACCOUNT_SKIN_REFRESH.mask();
    }

    private static ProtocolProfile negotiated(long capabilities) {
        return new ProtocolProfile(ProtocolProfile.Mode.NEGOTIATED, 2, capabilities, 1, 1, "test");
    }
}
