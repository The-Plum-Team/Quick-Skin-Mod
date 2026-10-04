package com.quickskin.mod.server.data;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerCooldownManagerTest {
    private static final String PLAID = "local_skin:sha256-aaaa";
    private static final String CHECKER = "local_skin:sha256-bbbb";

    @Test
    void returningToTheSkinWornBeforeAWithdrawalIsNoChange() {
        ServerCooldownManager cooldowns = ServerCooldownManager.getInstance();
        UUID player = UUID.randomUUID();
        try {
            assertTrue(cooldowns.isSkinChange(player, PLAID, null));
            cooldowns.recordWornSkin(player, PLAID);
            assertFalse(cooldowns.isSkinChange(player, PLAID, PLAID));

            // A CPM model became the look: the client withdrew its skin.
            cooldowns.recordWornSkin(player, "");
            assertFalse(cooldowns.isSkinChange(player, "", PLAID));
            assertFalse(cooldowns.isSkinChange(player, PLAID, ""));

            // Another skin is still a change the cooldown limits.
            assertTrue(cooldowns.isSkinChange(player, CHECKER, ""));
            cooldowns.recordWornSkin(player, CHECKER);
            assertTrue(cooldowns.isSkinChange(player, PLAID, ""));
        } finally {
            cooldowns.removePlayer(player);
        }
        assertTrue(cooldowns.isSkinChange(player, CHECKER, ""));
    }

    @Test
    void wearingNoSkinIsNeverAChange() {
        ServerCooldownManager cooldowns = ServerCooldownManager.getInstance();
        UUID player = UUID.randomUUID();
        assertFalse(cooldowns.isSkinChange(player, "", PLAID));
        assertFalse(cooldowns.isSkinChange(player, null, PLAID));
    }
}
