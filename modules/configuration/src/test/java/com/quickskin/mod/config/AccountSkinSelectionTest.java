package com.quickskin.mod.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountSkinSelectionTest {
    private static final String OWNER =
            AccountSkinPreferences.key(UUID.fromString("00000000-0000-0000-0000-00000000000a"), "Ana");
    private static final String VISITOR =
            AccountSkinPreferences.key(UUID.fromString("00000000-0000-0000-0000-00000000000b"), "Bea");
    private static final String OWNER_SKIN = "sha256-" + "a".repeat(64);
    private static final String CHOSEN_SKIN = "sha256-" + "c".repeat(64);
    private static final String SERVER_SKIN = "sha256-" + "5".repeat(64);
    private static final String SERVER_SKIN_ID = "local_skin:" + SERVER_SKIN;
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000b0");

    private final AccountSkinPreferences preferences = new AccountSkinPreferences();
    private final AccountSkinSession session = new AccountSkinSession();
    private final Object connection = new Object();
    /** The instance-wide ClientConfig.activeSkinHash that the mixins, menu and restorer read. */
    private String activeSkinHash = "";

    /** Joins as the given account through the projection the client runtime starts sessions with. */
    private AccountSkinPreferences.Projection join(String account, Object sessionConnection) {
        AccountSkinPreferences.Projection projection =
                preferences.project(account, activeSkinHash, false);
        activeSkinHash = projection.activeSkinHash();
        session.begin(PLAYER, sessionConnection, projection.hasSelection());
        return projection;
    }

    @Test
    void skinChosenBeforeJoiningWinsOverTheSkinSavedOnTheServer() {
        preferences.select(VISITOR, CHOSEN_SKIN);
        join(VISITOR, connection);

        assertEquals(CHOSEN_SKIN, activeSkinHash);
        assertFalse(session.awaitingServerState(PLAYER, connection));
        assertFalse(session.decide(PLAYER, connection, SERVER_SKIN_ID));
        assertFalse(session.adopted(PLAYER, connection));
        assertEquals(CHOSEN_SKIN, preferences.skinHash(VISITOR));

        // The choice keeps winning in every later world or server, whatever skin that one saved.
        Object otherWorld = new Object();
        join(VISITOR, otherWorld);
        assertFalse(session.awaitingServerState(PLAYER, otherWorld));
        assertFalse(session.decide(PLAYER, otherWorld, SERVER_SKIN_ID));
    }

    @Test
    void serverSavedSkinIsRestoredOnlyWhenThereIsNoNewerChoice() {
        join(VISITOR, connection);

        assertTrue(session.awaitingServerState(PLAYER, connection));
        assertTrue(session.decide(PLAYER, connection, SERVER_SKIN_ID));
        assertTrue(session.adopted(PLAYER, connection));
        assertFalse(session.awaitingServerState(PLAYER, connection));
    }

    @Test
    void lateOrStaleServerResponseDoesNotOverwriteTheNewerChoice() {
        Object earlierConnection = new Object();
        join(VISITOR, earlierConnection);
        join(VISITOR, connection);

        // A record of the earlier connection or of another player neither decides nor ends the wait.
        assertFalse(session.decide(PLAYER, earlierConnection, SERVER_SKIN_ID));
        assertFalse(session.decide(UUID.randomUUID(), connection, SERVER_SKIN_ID));
        assertTrue(session.awaitingServerState(PLAYER, connection));

        // The player chooses while the server's record is still on its way.
        preferences.select(VISITOR, CHOSEN_SKIN);
        session.selected();

        assertFalse(session.decide(PLAYER, connection, SERVER_SKIN_ID));
        assertFalse(session.adopted(PLAYER, connection));
        assertEquals(CHOSEN_SKIN, preferences.skinHash(VISITOR));
    }

    @Test
    void laterManualChoiceWinsAgain() {
        join(VISITOR, connection);
        assertTrue(session.decide(PLAYER, connection, SERVER_SKIN_ID));

        preferences.select(VISITOR, CHOSEN_SKIN);
        session.selected();

        // Further own-player updates of this connection are confirmations only.
        assertFalse(session.decide(PLAYER, connection, SERVER_SKIN_ID));
        // The next connection uploads the choice instead of waiting for the server.
        Object nextConnection = new Object();
        join(VISITOR, nextConnection);
        assertFalse(session.awaitingServerState(PLAYER, nextConnection));
        assertFalse(session.adopted(PLAYER, nextConnection));
    }

    @Test
    void anotherAccountDoesNotInheritTheSelectionOfTheInstance() {
        // The instance's selection predates per-account storage: its first account keeps it.
        activeSkinHash = OWNER_SKIN;
        AccountSkinPreferences.Projection owner = join(OWNER, connection);
        assertEquals(new AccountSkinPreferences.Projection(OWNER_SKIN, true, true), owner);
        assertFalse(session.awaitingServerState(PLAYER, connection));

        // The owner's skin is still the active one when the visitor joins: it stays the owner's.
        Object visitorConnection = new Object();
        AccountSkinPreferences.Projection visitor = join(VISITOR, visitorConnection);
        assertEquals(new AccountSkinPreferences.Projection("", true, false), visitor);
        assertTrue(session.awaitingServerState(PLAYER, visitorConnection));
        assertEquals("", preferences.skinHash(VISITOR));
        assertEquals(OWNER_SKIN, preferences.skinHash(OWNER));

        // The owner comes back to the skin selected before the visitor played.
        Object ownerConnection = new Object();
        assertEquals(new AccountSkinPreferences.Projection(OWNER_SKIN, true, true),
                join(OWNER, ownerConnection));
        assertFalse(session.awaitingServerState(PLAYER, ownerConnection));
        assertEquals(new AccountSkinPreferences.Projection(OWNER_SKIN, false, true),
                join(OWNER, new Object()));
    }

    @Test
    void skinRestoredFromTheServerNeverBecomesASelection() {
        join(VISITOR, connection);
        assertTrue(session.decide(PLAYER, connection, SERVER_SKIN_ID));
        // The network handler names the skin now worn in the instance-wide field.
        activeSkinHash = SERVER_SKIN;

        // No account inherits it at the next join: the server is asked again.
        Object nextConnection = new Object();
        assertEquals(new AccountSkinPreferences.Projection("", true, false),
                join(VISITOR, nextConnection));
        assertEquals("", preferences.skinHash(VISITOR));
        assertTrue(session.awaitingServerState(PLAYER, nextConnection));
        activeSkinHash = SERVER_SKIN;
        assertEquals("", join(OWNER, new Object()).activeSkinHash());
        assertEquals("", preferences.skinHash(OWNER));
    }

    @Test
    void activeCpmModelAndUnreadableAccountKeepTheInstanceWideSkin() {
        activeSkinHash = OWNER_SKIN;

        // An account that cannot be stored neither waits nor consumes the one-time migration.
        assertEquals(new AccountSkinPreferences.Projection(OWNER_SKIN, false, true),
                preferences.project(null, activeSkinHash, false));
        // A CPM model is instance-wide: no skin hash is projected and the session does not wait.
        assertEquals(new AccountSkinPreferences.Projection("", true, true),
                preferences.project(VISITOR, "", true));
        assertEquals("", preferences.skinHash(VISITOR));
        preferences.select(VISITOR, CHOSEN_SKIN);
        assertEquals(new AccountSkinPreferences.Projection("", false, true),
                preferences.project(VISITOR, "", true));
        assertEquals(CHOSEN_SKIN, preferences.skinHash(VISITOR));
    }

    @Test
    void selectionMadeBeforeTheFirstJoinIsNotReplacedByTheMigration() {
        preferences.select(VISITOR, CHOSEN_SKIN);

        assertTrue(preferences.migrate(OWNER, CHOSEN_SKIN));
        assertEquals("", preferences.skinHash(OWNER));
        assertEquals(CHOSEN_SKIN, preferences.skinHash(VISITOR));
    }

    @Test
    void serverRecordWithoutASavedSkinIsNotAdoptedAndEndsTheWait() {
        for (String serverSkinId : new String[] {"", null, "Steve", "local_skin:not-a-content-id"}) {
            join(VISITOR, connection);

            assertFalse(session.decide(PLAYER, connection, serverSkinId));
            assertFalse(session.adopted(PLAYER, connection));
            assertFalse(session.awaitingServerState(PLAYER, connection));
        }
    }

    @Test
    void adoptedSessionEndsWithItsConnection() {
        join(VISITOR, connection);
        assertTrue(session.decide(PLAYER, connection, SERVER_SKIN_ID));
        assertFalse(session.adopted(PLAYER, new Object()));
        assertFalse(session.adopted(UUID.randomUUID(), connection));

        session.clear();

        assertFalse(session.adopted(PLAYER, connection));
        assertFalse(session.awaitingServerState(PLAYER, connection));
    }

    @Test
    void sessionWithoutPlayerOrConnectionNeverWaits() {
        session.begin(null, connection, false);
        assertFalse(session.awaitingServerState(null, connection));

        session.begin(PLAYER, null, false);
        assertFalse(session.awaitingServerState(PLAYER, null));
    }

    @Test
    void keyRejectsIdentitiesThatCannotBeStored() {
        UUID profileId = UUID.fromString("00000000-0000-0000-0000-00000000000c");

        assertEquals(profileId + ":Cai", AccountSkinPreferences.key(profileId, "Cai"));
        assertEquals(profileId + ":" + "n".repeat(64),
                AccountSkinPreferences.key(profileId, "n".repeat(64)));
        assertNull(AccountSkinPreferences.key(null, "Cai"));
        assertNull(AccountSkinPreferences.key(profileId, null));
        assertNull(AccountSkinPreferences.key(profileId, ""));
        assertNull(AccountSkinPreferences.key(profileId, "n".repeat(65)));
        assertNull(AccountSkinPreferences.key(profileId, "Cai\n"));
    }

    @Test
    void selectIgnoresInvalidInputAndBoundsTheAccounts() {
        preferences.select(null, OWNER_SKIN);
        preferences.select("not-an-account", OWNER_SKIN);
        preferences.select(OWNER, "not-a-content-id");
        preferences.select(OWNER, "");
        assertEquals("", preferences.skinHash(OWNER));
        assertEquals("", preferences.skinHash(null));

        preferences.select(OWNER, OWNER_SKIN);
        for (int index = 0; index < 127; index++) {
            preferences.select(AccountSkinPreferences.key(new UUID(1L, index), "Guest"), CHOSEN_SKIN);
        }
        assertEquals(OWNER_SKIN, preferences.skinHash(OWNER));

        // The account that selected longest ago makes room for the 129th.
        preferences.select(VISITOR, CHOSEN_SKIN);
        assertEquals("", preferences.skinHash(OWNER));
        assertEquals(CHOSEN_SKIN, preferences.skinHash(VISITOR));

        preferences.remove(VISITOR);
        assertEquals("", preferences.skinHash(VISITOR));
    }

    @Test
    void normalizeDropsEntriesAHandEditedFileCouldContain() throws ReflectiveOperationException {
        Field skins = AccountSkinPreferences.class.getDeclaredField("skins");
        skins.setAccessible(true);
        Map<String, String> edited = new LinkedHashMap<>();
        edited.put(OWNER, OWNER_SKIN);
        edited.put("Ana", OWNER_SKIN);
        edited.put("00000000-0000-0000-0000-00000000000A:Ana", OWNER_SKIN);
        edited.put(VISITOR, "not-a-content-id");
        edited.put(VISITOR + "\t", CHOSEN_SKIN);
        skins.set(preferences, edited);

        preferences.normalize();

        assertEquals(Map.of(OWNER, OWNER_SKIN), skins.get(preferences));

        skins.set(preferences, null);
        preferences.normalize();
        assertEquals("", preferences.skinHash(OWNER));
    }
}
