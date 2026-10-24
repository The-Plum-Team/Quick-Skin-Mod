package com.quickskin.mod.server.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each player is told once per world, and the record survives a restart. */
class MissingModNoticeLedgerTest {
    @TempDir
    Path world;

    private final List<Runnable> queued = new ArrayList<>();
    private final UUID player = UUID.randomUUID();

    private MissingModNoticeLedger ledger(boolean queueAccepts) {
        return new MissingModNoticeLedger(operation -> queueAccepts && queued.add(operation));
    }

    private void runQueued() {
        queued.forEach(Runnable::run);
        queued.clear();
    }

    @Test
    void aPlayerIsToldOnlyOncePerWorldEvenAfterARestart() {
        MissingModNoticeLedger ledger = ledger(true);
        ledger.init(world);
        assertFalse(ledger.hasBeenNotified(player));

        ledger.recordNotified(player);
        assertTrue(ledger.hasBeenNotified(player), "known at once, before the file is written");
        runQueued();
        assertTrue(Files.exists(world.resolve("quickskin/missing_mod_notices/" + player)));

        MissingModNoticeLedger restarted = ledger(true);
        restarted.init(world);
        assertTrue(restarted.hasBeenNotified(player));
        assertFalse(restarted.hasBeenNotified(UUID.randomUUID()));
    }

    @Test
    void recordingTwiceKeepsOneMarker() {
        MissingModNoticeLedger ledger = ledger(true);
        ledger.init(world);
        ledger.recordNotified(player);
        ledger.recordNotified(player);
        runQueued();

        assertTrue(ledger.hasBeenNotified(player));
    }

    @Test
    void aFullQueueStillSparesThePlayerForTheRestOfTheSession() {
        MissingModNoticeLedger ledger = ledger(false);
        ledger.init(world);
        ledger.recordNotified(player);

        assertTrue(ledger.hasBeenNotified(player));
        assertFalse(Files.exists(world.resolve("quickskin/missing_mod_notices/" + player)));
    }

    @Test
    void withoutAWorldNobodyIsTold() {
        MissingModNoticeLedger ledger = ledger(true);
        assertTrue(ledger.hasBeenNotified(player), "not started");

        ledger.init(world);
        ledger.clear();
        assertTrue(ledger.hasBeenNotified(player), "stopped");
        ledger.recordNotified(player);
        assertTrue(queued.isEmpty());
    }

    @Test
    void anUnusableFolderTellsNobody() throws Exception {
        Files.createDirectories(world.resolve("quickskin"));
        Files.writeString(world.resolve("quickskin/missing_mod_notices"), "not a folder");
        MissingModNoticeLedger ledger = ledger(true);
        ledger.init(world);

        assertTrue(ledger.hasBeenNotified(player));
    }
}
