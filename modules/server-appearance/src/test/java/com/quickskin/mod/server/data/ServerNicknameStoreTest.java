package com.quickskin.mod.server.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The server remembers each player's nickname, refuses names that read like someone else's. */
class ServerNicknameStoreTest {
    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID NOTCH = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final long NOW = 1_000_000L;

    @TempDir
    Path world;

    private ServerNicknameStore store() {
        ServerNicknameStore store = new ServerNicknameStore();
        store.init(world);
        store.load();
        return store;
    }

    @Test
    void aRequestedNicknameIsKeptInItsCanonicalForm() {
        ServerNicknameStore store = store();

        ServerNicknameStore.Result result = store.request(STEVE, "Steve", "  Sir   Lancelot ", true, List.of(), NOW);

        assertEquals(ServerNicknameStore.Status.ACCEPTED, result.status());
        assertEquals("Sir Lancelot", result.nickname());
        assertEquals("", result.previous());
        assertEquals("Sir Lancelot", store.nickname(STEVE));
        assertEquals(ServerNicknameStore.Status.UNCHANGED,
                store.request(STEVE, "Steve", "Sir Lancelot", true, List.of(), NOW + 1).status());
    }

    @Test
    void theAccountNameOrABlankNicknameClearsIt() {
        ServerNicknameStore store = store();
        store.request(STEVE, "Steve", "Sir Lancelot", true, List.of(), NOW);

        ServerNicknameStore.Result cleared = store.request(
                STEVE, "Steve", "Steve", true, List.of(), NOW + ServerNicknameStore.CHANGE_COOLDOWN_MILLIS);

        assertEquals(ServerNicknameStore.Status.ACCEPTED, cleared.status());
        assertEquals("", cleared.nickname());
        assertEquals("Sir Lancelot", cleared.previous());
        assertEquals("", store.nickname(STEVE));
        assertEquals(ServerNicknameStore.Status.UNCHANGED,
                store.request(STEVE, "Steve", "   ", true, List.of(), NOW + 60_000).status());
    }

    @Test
    void aPlayerWaitsBetweenChanges() {
        ServerNicknameStore store = store();
        store.request(STEVE, "Steve", "Sir Lancelot", true, List.of(), NOW);

        ServerNicknameStore.Result early = store.request(STEVE, "Steve", "Galahad", true, List.of(), NOW + 4_000);

        assertEquals(ServerNicknameStore.Status.COOLDOWN, early.status());
        assertEquals("Sir Lancelot", early.nickname());
        assertEquals(ServerNicknameStore.CHANGE_COOLDOWN_MILLIS - 4_000, early.retryAfterMillis());
        assertEquals(ServerNicknameStore.Status.ACCEPTED, store.request(
                STEVE, "Steve", "Galahad", true, List.of(), NOW + ServerNicknameStore.CHANGE_COOLDOWN_MILLIS).status());
    }

    @Test
    void aNicknameMayNotReadLikeAnotherPlayer() {
        ServerNicknameStore store = store();
        store.request(ALEX, "Alex", "Morgana", true, List.of(), NOW);

        assertEquals(ServerNicknameStore.Status.TAKEN,
                store.request(STEVE, "Steve", "notch", true, List.of("Alex", "Notch"), NOW).status());
        assertEquals(ServerNicknameStore.Status.TAKEN,
                store.request(STEVE, "Steve", "MORGANA", true, List.of(), NOW).status());
        // Alex's account name is known from the store even while Alex is away
        assertEquals(ServerNicknameStore.Status.TAKEN,
                store.request(STEVE, "Steve", "alex", true, List.of(), NOW).status());
        // Another case of the player's own name only changes how it reads
        assertEquals(ServerNicknameStore.Status.ACCEPTED,
                store.request(STEVE, "Steve", "STEVE", true, List.of("Alex"), NOW).status());
    }

    @Test
    void anAbsentPlayersAccountNameStaysTheirs() {
        ServerNicknameStore store = store();
        // Alex joined once, without a nickname, and left
        store.playerJoined(ALEX, "Alex", NOW);

        assertEquals(ServerNicknameStore.Status.TAKEN,
                store.request(STEVE, "Steve", "ALEX", true, List.of(), NOW).status());
        // A Cyrillic А is drawn like the Latin one
        assertEquals(ServerNicknameStore.Status.TAKEN,
                store.request(STEVE, "Steve", "\u0410lex", true, List.of(), NOW).status());
        // Names the caller knows, such as operators and the whitelist, count too
        assertEquals(ServerNicknameStore.Status.TAKEN,
                store.request(STEVE, "Steve", "Herobrine", true, List.of("herobrine"), NOW).status());
        // The player's own name, listed among them, only changes how it reads
        assertEquals(ServerNicknameStore.Status.ACCEPTED,
                store.request(STEVE, "Steve", "STEVE", true, List.of("steve"), NOW).status());
    }

    @Test
    void aNicknameThatReadsLikeANewcomersNameIsClearedWhenTheyJoin() {
        ServerNicknameStore store = store();
        store.playerJoined(STEVE, "Steve", NOW);
        // Notch never joined this world, so the name was free
        assertEquals(ServerNicknameStore.Status.ACCEPTED,
                store.request(STEVE, "Steve", "notch", true, List.of(), NOW).status());

        List<ServerNicknameStore.Released> released = store.playerJoined(NOTCH, "Notch", NOW + 1);

        assertEquals(List.of(new ServerNicknameStore.Released(STEVE, "Steve", "notch", false)), released);
        assertEquals("", store.nickname(STEVE));
        assertTrue(store.playerJoined(NOTCH, "Notch", NOW + 2).isEmpty());
    }

    @Test
    void aModeratorCanLockAPlayersNickname() throws IOException {
        Path file = world.resolve("quickskin").resolve("nicknames.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"schema\":1,\"players\":{"
                + "\"" + STEVE + "\":{\"nickname\":\"\",\"name\":\"Steve\",\"lastSeen\":5,\"locked\":true}}}",
                StandardCharsets.UTF_8);
        ServerNicknameStore store = store();

        ServerNicknameStore.Result result = store.request(STEVE, "Steve", "Slur", true, List.of(), NOW);

        assertEquals(ServerNicknameStore.Status.LOCKED, result.status());
        assertEquals("", store.nickname(STEVE));
        store.playerJoined(STEVE, "Steve", NOW);
        store.saveNow();
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("\"locked\": true"));
        assertEquals(ServerNicknameStore.Status.LOCKED,
                store().request(STEVE, "Steve", "Slur", true, List.of(), NOW).status());
    }

    @Test
    void invalidAndDisabledRequestsChangeNothing() {
        ServerNicknameStore store = store();
        store.request(STEVE, "Steve", "Sir Lancelot", true, List.of(), NOW);

        ServerNicknameStore.Result invalid = store.request(STEVE, "Steve", "§cRed", true, List.of(), NOW + 60_000);
        ServerNicknameStore.Result disabled = store.request(STEVE, "Steve", "Galahad", false, List.of(), NOW + 60_000);

        assertEquals(ServerNicknameStore.Status.INVALID, invalid.status());
        assertEquals(ServerNicknameStore.Status.DISABLED, disabled.status());
        assertEquals("Sir Lancelot", disabled.nickname());
        assertEquals("Sir Lancelot", store.nickname(STEVE));
    }

    @Test
    void nicknamesSurviveARestartOfTheServer() {
        ServerNicknameStore store = store();
        store.request(STEVE, "Steve", "Sir Lancelot", true, List.of(), NOW);
        store.request(ALEX, "Alex", "Morgana", true, List.of(), NOW);
        store.saveNow();

        ServerNicknameStore restarted = store();

        assertEquals("Sir Lancelot", restarted.nickname(STEVE));
        assertEquals("Morgana", restarted.nickname(ALEX));
        assertEquals(2, restarted.nicknames().size());
    }

    @Test
    void accountNamesSurviveARestartToo() {
        ServerNicknameStore store = store();
        store.playerJoined(ALEX, "Alex", NOW);
        store.saveNow();

        assertEquals(ServerNicknameStore.Status.TAKEN,
                store().request(STEVE, "Steve", "alex", true, List.of(), NOW).status());
    }

    @Test
    void anUnreadableFileIsNeverOverwritten() throws IOException {
        Path file = world.resolve("quickskin").resolve("nicknames.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);

        ServerNicknameStore store = store();
        store.request(STEVE, "Steve", "Sir Lancelot", true, List.of(), NOW);
        store.saveNow();

        assertEquals("{ this is not json", Files.readString(file, StandardCharsets.UTF_8));
        assertEquals("Sir Lancelot", store.nickname(STEVE), "the running server still shows it");
    }

    @Test
    void invalidEntriesAreDroppedOneByOne() throws IOException {
        Path file = world.resolve("quickskin").resolve("nicknames.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"schema\":1,\"players\":{"
                + "\"" + STEVE + "\":{\"nickname\":\"Sir Lancelot\",\"name\":\"Steve\",\"lastSeen\":5},"
                + "\"" + ALEX + "\":{\"nickname\":\"§cRed\",\"name\":\"Alex\",\"lastSeen\":6},"
                + "\"not-a-uuid\":{\"nickname\":\"Morgana\"}}}", StandardCharsets.UTF_8);

        ServerNicknameStore store = store();

        assertEquals("Sir Lancelot", store.nickname(STEVE));
        assertEquals("", store.nickname(ALEX));
        assertEquals(1, store.nicknames().size());
    }

    @Test
    void theStoreForgetsWhoeverWasSeenLongestAgo() {
        ServerNicknameStore store = store();
        for (int i = 0; i <= ServerNicknameStore.MAX_ENTRIES; i++) {
            UUID playerId = new UUID(1L, i);
            store.request(playerId, "Player" + i, "Nick" + i, true, List.of(), NOW + i);
        }

        assertEquals(ServerNicknameStore.MAX_ENTRIES, store.nicknames().size());
        assertEquals("", store.nickname(new UUID(1L, 0)));
        assertEquals("Nick1", store.nickname(new UUID(1L, 1)));

        // A join makes a player the most recently seen
        store.playerJoined(new UUID(1L, 1), "Player1", NOW + 10_000_000);
        store.request(new UUID(2L, 0), "Late", "Latecomer", true, List.of(), NOW + 10_000_001);
        assertEquals("Nick1", store.nickname(new UUID(1L, 1)));
        assertEquals("", store.nickname(new UUID(1L, 2)));
        assertTrue(store.nicknames().containsKey(new UUID(2L, 0)));
    }

    @Test
    void anAdminsNicknameShowsOverThePlayersOwnUntilLifted() {
        ServerNicknameStore store = store();
        store.request(STEVE, "Steve", "Sir Lancelot", true, List.of(), NOW);

        ServerNicknameStore.Result assigned = store.assign(STEVE, "Steve", " Bravo ", List.of(), NOW + 1);

        assertEquals(ServerNicknameStore.Status.ACCEPTED, assigned.status());
        assertEquals("Bravo", assigned.nickname());
        assertEquals("Sir Lancelot", assigned.previous());
        assertEquals("Bravo", store.assignedNickname(STEVE));
        assertEquals("Sir Lancelot", store.nickname(STEVE), "the player's own stays underneath");
        // No cooldown for admins
        assertEquals(ServerNicknameStore.Status.ACCEPTED,
                store.assign(STEVE, "Steve", "Charlie", List.of(), NOW + 2).status());
        assertEquals(ServerNicknameStore.Status.UNCHANGED,
                store.assign(STEVE, "Steve", "Charlie", List.of(), NOW + 3).status());

        assertEquals("Charlie", store.lift(STEVE));
        assertEquals("", store.assignedNickname(STEVE));
        assertEquals("Sir Lancelot", store.nickname(STEVE));
        assertEquals("", store.lift(STEVE));
    }

    @Test
    void aPlayerCannotChangeTheirNicknameWhileAnAdminsShows() {
        ServerNicknameStore store = store();
        store.request(STEVE, "Steve", "Sir Lancelot", true, List.of(), NOW);
        store.assign(STEVE, "Steve", "Bravo", List.of(), NOW + 1);

        ServerNicknameStore.Result refused = store.request(
                STEVE, "Steve", "Galahad", true, List.of(), NOW + 60_000);
        // Their client asks again for the nickname they chose when they join: nothing to refuse
        ServerNicknameStore.Result again = store.request(
                STEVE, "Steve", "Sir Lancelot", true, List.of(), NOW + 60_000);

        assertEquals(ServerNicknameStore.Status.ASSIGNED, refused.status());
        assertEquals("Bravo", refused.nickname());
        assertEquals(ServerNicknameStore.Status.UNCHANGED, again.status());
        assertEquals("Sir Lancelot", store.nickname(STEVE));
    }

    @Test
    void anAdminsNicknameFollowsTheSameRules() {
        ServerNicknameStore store = store();
        store.playerJoined(ALEX, "Alex", NOW);
        store.request(NOTCH, "Notch", "Morgana", true, List.of(), NOW);

        assertEquals(ServerNicknameStore.Status.TAKEN, store.assign(STEVE, "Steve", "alex", List.of(), NOW).status());
        assertEquals(ServerNicknameStore.Status.TAKEN, store.assign(STEVE, "Steve", "MORGANA", List.of(), NOW).status());
        assertEquals(ServerNicknameStore.Status.TAKEN,
                store.assign(STEVE, "Steve", "Herobrine", List.of("Herobrine"), NOW).status());
        assertEquals(ServerNicknameStore.Status.INVALID, store.assign(STEVE, "Steve", "§cRed", List.of(), NOW).status());
        assertEquals(ServerNicknameStore.Status.INVALID, store.assign(STEVE, "Steve", "  ", List.of(), NOW).status());
        assertEquals(ServerNicknameStore.Status.INVALID, store.assign(STEVE, "Steve", "Steve", List.of(), NOW).status());

        store.assign(STEVE, "Steve", "Bravo", List.of(), NOW);
        // Nobody else may take a name an admin gave, nor ask for it
        assertEquals(ServerNicknameStore.Status.TAKEN, store.assign(ALEX, "Alex", "bravo", List.of(), NOW).status());
        assertEquals(ServerNicknameStore.Status.TAKEN,
                store.request(ALEX, "Alex", "BRAVO", true, List.of(), NOW).status());
        assertFalse(store.isAvailable(ALEX, "Alex", "Bravo", List.of()));
        assertTrue(store.isAvailable(STEVE, "Steve", "Bravo", List.of()));
    }

    @Test
    void clearingTakesAwayBothNicknames() {
        ServerNicknameStore store = store();
        store.request(STEVE, "Steve", "Sir Lancelot", true, List.of(), NOW);
        store.assign(STEVE, "Steve", "Bravo", List.of(), NOW);

        assertTrue(store.clearNicknames(STEVE));

        assertEquals("", store.nickname(STEVE));
        assertEquals("", store.assignedNickname(STEVE));
        assertFalse(store.clearNicknames(STEVE));
    }

    @Test
    void anAdminCanLockAndUnlockAPlayersNickname() {
        ServerNicknameStore store = store();
        store.request(STEVE, "Steve", "Sir Lancelot", true, List.of(), NOW);

        assertTrue(store.setLocked(STEVE, "Steve", true, NOW));
        assertFalse(store.setLocked(STEVE, "Steve", true, NOW));
        assertTrue(store.isLocked(STEVE));
        assertEquals(ServerNicknameStore.Status.LOCKED,
                store.request(STEVE, "Steve", "Galahad", true, List.of(), NOW + 60_000).status());
        // An admin's nickname shows on a locked player too
        assertEquals(ServerNicknameStore.Status.ACCEPTED,
                store.assign(STEVE, "Steve", "Bravo", List.of(), NOW + 60_000).status());

        assertTrue(store.setLocked(STEVE, "Steve", false, NOW));
        assertFalse(store.isLocked(STEVE));
        assertFalse(store.setLocked(ALEX, "Alex", false, NOW), "unlocking an unknown player changes nothing");
    }

    @Test
    void anAdminsNicknameAndLocksSurviveARestart() {
        ServerNicknameStore store = store();
        store.request(STEVE, "Steve", "Sir Lancelot", true, List.of(), NOW);
        store.assign(STEVE, "Steve", "Bravo", List.of(), NOW);
        store.playerJoined(ALEX, "Alex", NOW);
        store.setLocked(ALEX, "Alex", true, NOW);
        store.saveNow();

        ServerNicknameStore restarted = store();

        assertEquals("Bravo", restarted.assignedNickname(STEVE));
        assertEquals("Sir Lancelot", restarted.nickname(STEVE));
        assertTrue(restarted.isLocked(ALEX));
        assertEquals("", restarted.assignedNickname(ALEX));
    }

    @Test
    void anAdminsNicknameThatReadsLikeANewcomersNameIsTakenBack() {
        ServerNicknameStore store = store();
        store.request(STEVE, "Steve", "Sir Lancelot", true, List.of(), NOW);
        store.assign(STEVE, "Steve", "Notch", List.of(), NOW);

        List<ServerNicknameStore.Released> released = store.playerJoined(NOTCH, "Notch", NOW + 1);

        assertEquals(List.of(new ServerNicknameStore.Released(STEVE, "Steve", "Notch", true)), released);
        assertEquals("", store.assignedNickname(STEVE));
        assertEquals("Sir Lancelot", store.nickname(STEVE));
    }

    @Test
    void everyAdminNicknameIsTakenBackAtOnceAlsoOfAbsentPlayers() {
        ServerNicknameStore store = store();
        store.request(STEVE, "Steve", "Sir Lancelot", true, List.of(), NOW);
        store.assign(STEVE, "Steve", "Bravo", List.of(), NOW);
        store.assign(ALEX, "Alex", "Charlie", List.of(), NOW);

        assertEquals(List.of(STEVE, ALEX), store.liftAll());

        assertEquals("", store.assignedNickname(STEVE));
        assertEquals("", store.assignedNickname(ALEX));
        assertEquals("Sir Lancelot", store.nickname(STEVE));
        assertTrue(store.isAvailable(NOTCH, "Notch", "Bravo", List.of()), "the names are free again");
        assertTrue(store.liftAll().isEmpty());
    }
}
