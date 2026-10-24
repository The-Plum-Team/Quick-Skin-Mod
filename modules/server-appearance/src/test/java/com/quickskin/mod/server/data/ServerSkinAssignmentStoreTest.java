package com.quickskin.mod.server.data;

import com.quickskin.mod.networking.PoolSkinEntry;
import com.quickskin.mod.networking.TextureTransferLimits;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The skins admins gave players, and the world's skin pool, kept across restarts. */
class ServerSkinAssignmentStoreTest {
    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String GUARD = "local_skin:sha256-" + "a".repeat(64);
    private static final String KNIGHT = "local_skin:sha256-" + "b".repeat(64);

    @TempDir
    Path world;

    private ServerSkinAssignmentStore store() {
        ServerSkinAssignmentStore store = new ServerSkinAssignmentStore();
        store.init(world);
        store.load();
        return store;
    }

    @Test
    void onlyStrongLocalSkinsWithAnExplicitModelAreGiven() {
        assertTrue(ServerSkinAssignmentStore.isValid(GUARD, "slim"));
        assertTrue(ServerSkinAssignmentStore.isValid(GUARD, "classic"));
        assertFalse(ServerSkinAssignmentStore.isValid(GUARD, "auto"));
        assertFalse(ServerSkinAssignmentStore.isValid("local_skin:" + "a".repeat(40), "classic"), "SHA-1 ids are legacy");
        assertFalse(ServerSkinAssignmentStore.isValid("local_cape:sha256-" + "a".repeat(64), "classic"));
        assertFalse(ServerSkinAssignmentStore.isValid("Notch", "classic"));
    }

    @Test
    void aSkinIsGivenAndTakenBack() {
        ServerSkinAssignmentStore store = store();

        assertTrue(store.assign(STEVE, GUARD, "slim"));
        assertFalse(store.assign(STEVE, GUARD, "slim"), "the same skin again changes nothing");
        assertEquals(new ServerSkinAssignmentStore.AssignedSkin(GUARD, "slim"), store.assigned(STEVE));
        assertEquals("sha256-" + "a".repeat(64), store.assigned(STEVE).hash());
        assertFalse(store.assign(ALEX, GUARD, "auto"));
        assertNull(store.assigned(ALEX));

        assertEquals(GUARD, store.lift(STEVE).skinId());
        assertNull(store.assigned(STEVE));
        assertNull(store.lift(STEVE));
    }

    @Test
    void thePoolTakesEachNameAndSkinOnceUpToItsSize() {
        ServerSkinAssignmentStore store = store();

        assertEquals(ServerSkinAssignmentStore.PoolAddResult.ADDED, store.addToPool("Guard", GUARD, "classic"));
        assertEquals(ServerSkinAssignmentStore.PoolAddResult.NAME_TAKEN, store.addToPool("guard", KNIGHT, "classic"));
        assertEquals(ServerSkinAssignmentStore.PoolAddResult.DUPLICATE, store.addToPool("Other", GUARD, "slim"));
        assertEquals(ServerSkinAssignmentStore.PoolAddResult.INVALID, store.addToPool("two words", KNIGHT, "slim"));
        assertEquals("Guard", store.poolSkin("GUARD").name());

        for (int i = store.pool().size(); i < TextureTransferLimits.MAX_POOL_SKINS; i++) {
            String id = "local_skin:sha256-" + String.format("%064x", i + 100);
            assertEquals(ServerSkinAssignmentStore.PoolAddResult.ADDED, store.addToPool("skin" + i, id, "classic"));
        }
        assertEquals(ServerSkinAssignmentStore.PoolAddResult.FULL, store.addToPool("Knight", KNIGHT, "classic"));

        assertEquals("Guard", store.removeFromPool("guard").name());
        assertNull(store.removeFromPool("guard"));
        assertEquals(ServerSkinAssignmentStore.PoolAddResult.ADDED, store.addToPool("Knight", KNIGHT, "classic"));
    }

    @Test
    void assignedSkinsAndThePoolSurviveARestart() {
        ServerSkinAssignmentStore store = store();
        store.assign(STEVE, GUARD, "slim");
        store.addToPool("Knight", KNIGHT, "classic");
        store.saveNow();

        ServerSkinAssignmentStore restarted = store();

        assertEquals(new ServerSkinAssignmentStore.AssignedSkin(GUARD, "slim"), restarted.assigned(STEVE));
        assertEquals(List.of(new PoolSkinEntry("Knight", KNIGHT, "classic")), restarted.pool());
    }

    @Test
    void aFullStoreGivesNoNewPlayerASkinAndEvictsNobody() {
        ServerSkinAssignmentStore store = store();
        for (int i = 0; i < ServerSkinAssignmentStore.MAX_ASSIGNED; i++) {
            assertTrue(store.assign(new UUID(7L, i), GUARD, "classic"));
        }

        assertFalse(store.hasRoomFor(STEVE));
        assertFalse(store.assign(STEVE, GUARD, "classic"));
        assertNull(store.assigned(STEVE));
        assertTrue(store.assign(new UUID(7L, 0), KNIGHT, "slim"), "a player who has one may get another");
        assertEquals(ServerSkinAssignmentStore.MAX_ASSIGNED, store.assignedSkins().size());

        assertEquals(ServerSkinAssignmentStore.MAX_ASSIGNED, store.liftAll().size());
        assertTrue(store.assignedSkins().isEmpty());
        assertTrue(store.hasRoomFor(STEVE));
    }

    @Test
    void anUnreadableFileIsNeverOverwrittenAndBadEntriesAreDropped() throws IOException {
        Path file = world.resolve("quickskin").resolve("assigned-skins.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"schema\":1,\"players\":{"
                + "\"" + STEVE + "\":{\"skin\":\"" + GUARD + "\",\"model\":\"slim\"},"
                + "\"" + ALEX + "\":{\"skin\":\"Notch\",\"model\":\"slim\"},"
                + "\"not-a-uuid\":{\"skin\":\"" + GUARD + "\",\"model\":\"slim\"}},"
                + "\"pool\":[{\"name\":\"bad name\",\"skin\":\"" + KNIGHT + "\",\"model\":\"slim\"},"
                + "{\"name\":\"Knight\",\"skin\":\"" + KNIGHT + "\",\"model\":\"slim\"}]}",
                StandardCharsets.UTF_8);
        ServerSkinAssignmentStore store = store();
        assertEquals(GUARD, store.assigned(STEVE).skinId());
        assertNull(store.assigned(ALEX));
        assertEquals(1, store.pool().size());

        Files.writeString(file, "{ not json", StandardCharsets.UTF_8);
        ServerSkinAssignmentStore broken = store();
        broken.assign(ALEX, KNIGHT, "classic");
        broken.saveNow();
        assertEquals("{ not json", Files.readString(file, StandardCharsets.UTF_8));
    }
}
