package com.quickskin.mod.config;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each launcher account keeps its own nickname, in the canonical form servers accept. */
class AccountNicknamesTest {
    private static final String STEVE =
            AccountSkinPreferences.key(UUID.fromString("00000000-0000-0000-0000-000000000001"), "Steve");
    private static final String ALEX =
            AccountSkinPreferences.key(UUID.fromString("00000000-0000-0000-0000-000000000002"), "Alex");

    @Test
    void eachAccountKeepsItsOwnNickname() {
        AccountNicknames nicknames = new AccountNicknames();

        assertTrue(nicknames.set(STEVE, "  Sir   Lancelot "));
        assertTrue(nicknames.set(ALEX, "Morgana"));

        assertEquals("Sir Lancelot", nicknames.nickname(STEVE));
        assertEquals("Morgana", nicknames.nickname(ALEX));
        assertEquals("", nicknames.nickname(null));
        assertFalse(nicknames.set(STEVE, "Sir Lancelot"), "the same nickname is no change");
    }

    @Test
    void aBlankNicknameOrTheAccountsOwnNameRemovesIt() {
        AccountNicknames nicknames = new AccountNicknames();
        nicknames.set(STEVE, "Sir Lancelot");

        assertTrue(nicknames.set(STEVE, "Steve"));
        assertEquals("", nicknames.nickname(STEVE));
        assertFalse(nicknames.set(STEVE, "   "), "there was nothing left to remove");
        // Another case of the own name is a nickname, which only changes how the name reads
        assertTrue(nicknames.set(STEVE, "steve"));
        assertEquals("steve", nicknames.nickname(STEVE));
    }

    @Test
    void anInvalidNicknameOrAccountChangesNothing() {
        AccountNicknames nicknames = new AccountNicknames();
        nicknames.set(STEVE, "Sir Lancelot");

        assertFalse(nicknames.set(STEVE, "§cRed"));
        assertFalse(nicknames.set("not-an-account", "Morgana"));
        assertEquals("Sir Lancelot", nicknames.nickname(STEVE));
    }

    @Test
    void storedNicknamesAreRepairedWhenTheFileIsRead() {
        ClientConfig config = ClientConfig.parse("{\"accountNicknames\":{\"nicknames\":{"
                + "\"" + STEVE + "\":\"Sir Lancelot\","
                + "\"" + ALEX + "\":\" Untrimmed\","
                + "\"bad-key\":\"Morgana\"}}}");

        assertEquals("Sir Lancelot", config.accountNicknames.nickname(STEVE));
        assertEquals("", config.accountNicknames.nickname(ALEX));
        assertEquals("", ClientConfig.parse("{}").accountNicknames.nickname(STEVE));
    }

    @Test
    void theStoreKeepsTheMostRecentAccounts() {
        AccountNicknames nicknames = new AccountNicknames();
        for (int i = 0; i < 200; i++) {
            String key = AccountSkinPreferences.key(new UUID(0L, i), "Player" + i);
            nicknames.set(key, "Nick" + i);
        }
        assertEquals("", nicknames.nickname(AccountSkinPreferences.key(new UUID(0L, 0), "Player0")));
        assertEquals("Nick199",
                nicknames.nickname(AccountSkinPreferences.key(new UUID(0L, 199), "Player199")));
    }
}
