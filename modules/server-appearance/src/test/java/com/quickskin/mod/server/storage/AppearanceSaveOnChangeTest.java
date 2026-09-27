package com.quickskin.mod.server.storage;

import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.server.concurrent.ServerCacheIoExecutor;
import com.quickskin.mod.server.data.ServerPlayerAppearanceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppearanceSaveOnChangeTest {
    @TempDir
    Path world;

    private final ServerCacheIoExecutor worker = ServerCacheIoExecutor.getInstance();
    private final ServerAppearanceStorage appearances = ServerAppearanceStorage.getInstance();
    private final ServerPlayerAppearanceRepository players = ServerPlayerAppearanceRepository.getInstance();
    private final UUID player = UUID.randomUUID();

    @BeforeEach
    void openWorld() {
        worker.start();
        appearances.init(world);
    }

    @AfterEach
    void releaseWorld() {
        worker.close();
        players.clear();
        appearances.clear();
    }

    @Test
    void aCommittedChangeIsSavedWithoutWaitingForTheDisconnect() {
        assertTrue(players.trySetAppearance(new PlayerAppearance(player, "", "", "slim")));

        appearances.scheduleSavePlayerAppearance(player);
        worker.close();

        assertEquals("slim", reload().getModel());
    }

    @Test
    void aSynchronousSaveIsNeverOverwrittenByAnOlderQueuedOne() throws Exception {
        CountDownLatch release = holdWorker();
        assertTrue(players.trySetAppearance(new PlayerAppearance(player, "", "", "slim")));
        appearances.scheduleSavePlayerAppearance(player);

        assertTrue(players.trySetAppearance(new PlayerAppearance(player, "", "", "classic")));
        appearances.savePlayerAppearance(player);
        release.countDown();
        worker.close();

        assertEquals("classic", reload().getModel());
    }

    @Test
    void aQueuedSaveKeepsOnlyTheLatestChange() throws Exception {
        CountDownLatch release = holdWorker();
        assertTrue(players.trySetAppearance(new PlayerAppearance(player, "", "", "slim")));
        appearances.scheduleSavePlayerAppearance(player);
        assertTrue(players.trySetAppearance(new PlayerAppearance(player, "", "", "classic")));
        appearances.scheduleSavePlayerAppearance(player);
        release.countDown();
        worker.close();

        assertEquals("classic", reload().getModel());
    }

    @Test
    void aQueuedSaveNeverWritesIntoAReleasedWorld() throws Exception {
        CountDownLatch release = holdWorker();
        assertTrue(players.trySetAppearance(new PlayerAppearance(player, "", "", "slim")));
        appearances.scheduleSavePlayerAppearance(player);

        appearances.clear();
        release.countDown();
        worker.close();

        assertFalse(Files.exists(world.resolve("quickskin/appearances/" + player + ".json")));
    }

    /** Occupies the single worker thread until the returned latch is released. */
    private CountDownLatch holdWorker() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        assertTrue(worker.submit(() -> {
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }));
        assertTrue(started.await(10, TimeUnit.SECONDS));
        return release;
    }

    private PlayerAppearance reload() {
        players.clear();
        PlayerAppearance loaded = appearances.loadPlayerAppearance(player);
        assertTrue(loaded != null, "no saved appearance");
        return loaded;
    }
}
