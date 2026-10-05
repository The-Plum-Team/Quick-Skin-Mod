package com.quickskin.mod.server.vanilla;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.quickskin.mod.server.vanilla.AccountSkinShareService.Admission;
import static com.quickskin.mod.server.vanilla.AccountSkinShareService.Outcome;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountSkinShareServiceTest {
    private static final UUID PLAYER = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");
    private static final UUID SECOND = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String OLD_SKIN = "http://textures.minecraft.net/texture/old";
    private static final String NEW_SKIN = "http://textures.minecraft.net/texture/new";
    private static final AccountSkinShareService.Policy POLICY = new AccountSkinShareService.Policy(
            1_000L, List.of(1_000L, 2_000L), 10_000L, 3, 1_000L, 4);

    private final ManualClock clock = new ManualClock();
    private final ManualScheduler scheduler = new ManualScheduler(clock);
    private final FakeFetcher fetcher = new FakeFetcher();
    private final FakeTarget target = new FakeTarget();
    private final List<String> outcomes = new ArrayList<>();
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final Object session = new Object();
    private final AccountSkinShareService service = new AccountSkinShareService(
            POLICY, fetcher, clock::now, enabled::get, () -> scheduler,
            (player, outcome) -> outcomes.add(player + ":" + outcome));

    @AfterEach
    void close() {
        service.close();
    }

    @Test
    void appliesTheNewSignedTexturesAfterMojangReportsThem() {
        service.start(target);
        target.textures = SessionProfileFixtures.signed(PLAYER, OLD_SKIN, null, 1L);
        fetcher.respond(200, SessionProfileFixtures.response(PLAYER, NEW_SKIN, "slim", 2L));

        assertEquals(Admission.ACCEPTED, service.request(PLAYER, session));
        assertEquals(0, fetcher.calls, "the lookup never runs inside the request");
        scheduler.advance(1_000L);

        assertEquals(1, fetcher.calls);
        assertEquals(1, target.applied.size());
        assertEquals(new AccountTextures(NEW_SKIN, "slim", null),
                AccountTextures.decode(target.applied.get(0).value(), PLAYER));
        assertEquals(List.of(PLAYER + ":APPLIED"), outcomes);
    }

    @Test
    void pollsWithBackoffUntilTheSessionServerStopsReportingThePreviousSkin() {
        service.start(target);
        target.textures = SessionProfileFixtures.signed(PLAYER, OLD_SKIN, null, 1L);
        fetcher.respond(200, SessionProfileFixtures.response(PLAYER, OLD_SKIN, null, 5L));
        fetcher.respond(200, SessionProfileFixtures.response(PLAYER, NEW_SKIN, null, 6L));

        service.request(PLAYER, session);
        scheduler.advance(1_000L);
        assertEquals(1, fetcher.calls);
        assertTrue(target.applied.isEmpty(), "an unchanged appearance is not applied");

        scheduler.advance(999L);
        assertEquals(1, fetcher.calls);
        scheduler.advance(1L);
        assertEquals(2, fetcher.calls);
        assertEquals(1, target.applied.size());
        assertEquals(List.of(PLAYER + ":APPLIED"), outcomes);
    }

    @Test
    void endsUnchangedWhenEveryAttemptStillShowsTheSameSkin() {
        service.start(target);
        target.textures = SessionProfileFixtures.signed(PLAYER, OLD_SKIN, null, 1L);
        for (int i = 0; i < 3; i++) {
            fetcher.respond(200, SessionProfileFixtures.response(PLAYER, OLD_SKIN, null, 10L + i));
        }

        service.request(PLAYER, session);
        scheduler.advance(60_000L);

        assertEquals(3, fetcher.calls, "one first attempt plus one per retry delay");
        assertTrue(target.applied.isEmpty());
        assertEquals(List.of(PLAYER + ":UNCHANGED"), outcomes);
    }

    @Test
    void retriesTransientFailuresAndHonoursRetryAfter() {
        service.start(target);
        fetcher.fail();
        fetcher.respond(429, "", 5_000L);
        fetcher.respond(503, "", 0L);

        service.request(PLAYER, session);
        scheduler.advance(1_000L);
        assertEquals(1, fetcher.calls);
        scheduler.advance(1_000L);
        assertEquals(2, fetcher.calls);
        scheduler.advance(4_999L);
        assertEquals(2, fetcher.calls, "Retry-After outranks the shorter backoff");
        scheduler.advance(1L);
        assertEquals(3, fetcher.calls);
        assertEquals(List.of(PLAYER + ":GAVE_UP"), outcomes);
    }

    @Test
    void invalidOrForeignResponsesNeverReachTheProfile() {
        service.start(target);
        fetcher.respond(200, SessionProfileFixtures.response(SECOND, NEW_SKIN, null, 1L));

        service.request(PLAYER, session);
        scheduler.advance(10_000L);

        assertTrue(target.applied.isEmpty());
        assertEquals(List.of(PLAYER + ":REJECTED"), outcomes);

        service.request(SECOND, session);
        fetcher.respond(404, "", 0L);
        scheduler.advance(10_000L);
        assertEquals(PLAYER + ":REJECTED", outcomes.get(0));
        assertEquals(SECOND + ":NO_PROFILE", outcomes.get(1));
    }

    @Test
    void isANoOpWhenDisabledOfflineOrStopped() {
        assertEquals(Admission.NOT_RUNNING, service.request(PLAYER, session));

        service.start(target);
        enabled.set(false);
        assertEquals(Admission.DISABLED, service.request(PLAYER, session));

        enabled.set(true);
        target.online = false;
        assertEquals(Admission.OFFLINE_MODE, service.request(PLAYER, session));
        scheduler.advance(120_000L);

        assertEquals(0, fetcher.calls);
        assertEquals(0, service.trackedPlayers());
        assertEquals(Admission.INVALID, service.request(null, session));
        assertEquals(Admission.INVALID, service.request(PLAYER, null));
    }

    @Test
    void aReportDuringARoundRearmsExactlyOneMoreRoundAfterTheInterval() {
        service.start(target);
        target.textures = SessionProfileFixtures.signed(PLAYER, OLD_SKIN, null, 1L);
        fetcher.respond(200, SessionProfileFixtures.response(PLAYER, NEW_SKIN, null, 2L));
        fetcher.respond(200, SessionProfileFixtures.response(PLAYER, OLD_SKIN, "slim", 3L));

        assertEquals(Admission.ACCEPTED, service.request(PLAYER, session));
        assertEquals(Admission.COALESCED, service.request(PLAYER, session));
        assertEquals(Admission.COALESCED, service.request(PLAYER, session));
        scheduler.advance(1_000L);
        assertEquals(1, target.applied.size());

        scheduler.advance(9_999L);
        assertEquals(1, fetcher.calls, "the next round waits for the per-player interval");
        scheduler.advance(1L);
        assertEquals(2, fetcher.calls);
        assertEquals(2, target.applied.size());
        scheduler.advance(60_000L);
        assertEquals(2, fetcher.calls, "three reports cost two rounds, not three");
    }

    @Test
    void aReportWhileCoolingWaitsForTheInterval() {
        service.start(target);
        target.textures = SessionProfileFixtures.signed(PLAYER, OLD_SKIN, null, 1L);
        fetcher.respond(200, SessionProfileFixtures.response(PLAYER, NEW_SKIN, null, 2L));
        fetcher.respond(200, SessionProfileFixtures.response(PLAYER, OLD_SKIN, null, 3L));

        service.request(PLAYER, session);
        scheduler.advance(2_000L);
        assertEquals(1, fetcher.calls);
        assertEquals(Admission.ACCEPTED, service.request(PLAYER, session));
        scheduler.advance(8_999L);
        assertEquals(1, fetcher.calls);
        scheduler.advance(1L);
        assertEquals(2, fetcher.calls);

        scheduler.advance(60_000L);
        assertEquals(0, service.trackedPlayers(), "a finished player is forgotten after cooling");
    }

    @Test
    void theServerWideBucketPacesLookups() {
        AccountSkinShareService.Policy burstOfOne = new AccountSkinShareService.Policy(
                0L, List.of(), 0L, 1, 5_000L, 8);
        AccountSkinShareService paced = new AccountSkinShareService(
                burstOfOne, fetcher, clock::now, enabled::get, () -> scheduler, (p, o) -> { });
        try {
            paced.start(target);
            for (int i = 0; i < 3; i++) fetcher.respond(404, "", 0L);
            paced.request(PLAYER, session);
            paced.request(SECOND, session);
            paced.request(UUID.randomUUID(), session);

            scheduler.advance(0L);
            assertEquals(1, fetcher.calls);
            scheduler.advance(4_999L);
            assertEquals(1, fetcher.calls);
            scheduler.advance(1L);
            assertEquals(2, fetcher.calls);
            scheduler.advance(5_000L);
            assertEquals(3, fetcher.calls);
        } finally {
            paced.close();
        }
    }

    @Test
    void trackedPlayersAreBounded() {
        service.start(target);
        for (int i = 0; i < POLICY.maxTrackedPlayers(); i++) {
            assertEquals(Admission.ACCEPTED, service.request(UUID.randomUUID(), session));
        }
        assertEquals(Admission.CAPACITY, service.request(UUID.randomUUID(), session));
    }

    @Test
    void disconnectAndReconnectUseTheExactSession() {
        service.start(target);
        Object replacement = new Object();
        target.textures = SessionProfileFixtures.signed(PLAYER, OLD_SKIN, null, 1L);

        service.request(PLAYER, session);
        service.playerDisconnected(PLAYER, replacement);
        assertEquals(1, service.trackedPlayers(), "another session cannot cancel this round");
        service.playerDisconnected(PLAYER, session);
        assertEquals(0, service.trackedPlayers());
        scheduler.advance(60_000L);
        assertEquals(0, fetcher.calls);

        fetcher.respond(200, SessionProfileFixtures.response(PLAYER, NEW_SKIN, null, 2L));
        service.request(PLAYER, session);
        assertEquals(Admission.ACCEPTED, service.request(PLAYER, replacement));
        scheduler.advance(1_000L);
        assertEquals(1, fetcher.calls, "a reconnect replaces the earlier session's round");
        assertSame(replacement, target.appliedSessions.get(0));
    }

    @Test
    void aSessionThatLeftBeforeTheAnswerIsNotTouched() {
        service.start(target);
        fetcher.respond(200, SessionProfileFixtures.response(PLAYER, NEW_SKIN, null, 2L));
        target.gone = true;

        service.request(PLAYER, session);
        scheduler.advance(1_000L);

        assertTrue(target.applied.isEmpty());
        assertEquals(List.of(PLAYER + ":SESSION_GONE"), outcomes);
    }

    @Test
    void closeCancelsEverything() {
        service.start(target);
        service.request(PLAYER, session);
        service.close();
        scheduler.advance(60_000L);

        assertEquals(0, fetcher.calls);
        assertTrue(scheduler.shutdown);
        assertEquals(Admission.NOT_RUNNING, service.request(PLAYER, session));
    }

    private static final class ManualClock {
        long now = 1_000_000L;

        long now() {
            return now;
        }
    }

    private static final class ManualScheduler implements AccountSkinShareService.Scheduler {
        private final ManualClock clock;
        private final List<Task> tasks = new ArrayList<>();
        private long sequence;
        boolean shutdown;

        ManualScheduler(ManualClock clock) {
            this.clock = clock;
        }

        @Override
        public AccountSkinShareService.ScheduledTask schedule(Runnable runnable, long delayMillis) {
            Task task = new Task(clock.now + delayMillis, sequence++, runnable);
            tasks.add(task);
            return () -> task.cancelled = true;
        }

        @Override
        public void shutdown() {
            shutdown = true;
            tasks.clear();
        }

        void advance(long millis) {
            long end = clock.now + millis;
            while (true) {
                Task next = tasks.stream().filter(task -> !task.cancelled && task.due <= end)
                        .min(Comparator.comparingLong((Task task) -> task.due)
                                .thenComparingLong(task -> task.order))
                        .orElse(null);
                if (next == null) break;
                tasks.remove(next);
                clock.now = Math.max(clock.now, next.due);
                next.runnable.run();
            }
            clock.now = end;
        }

        private static final class Task {
            final long due;
            final long order;
            final Runnable runnable;
            boolean cancelled;

            Task(long due, long order, Runnable runnable) {
                this.due = due;
                this.order = order;
                this.runnable = runnable;
            }
        }
    }

    private static final class FakeFetcher implements SessionProfileFetcher {
        private final Deque<Object> answers = new ArrayDeque<>();
        int calls;

        void respond(int status, String body) {
            respond(status, body, 0L);
        }

        void respond(int status, String body, long retryAfter) {
            answers.add(new Response(status, body, retryAfter));
        }

        void fail() {
            answers.add(new IOException("simulated network failure"));
        }

        @Override
        public Response fetch(UUID profileId) throws IOException {
            calls++;
            Object answer = answers.poll();
            if (answer == null) return new Response(503, "", 0L);
            if (answer instanceof IOException error) throw error;
            return (Response) answer;
        }
    }

    private static final class FakeTarget implements AccountSkinShareService.Target {
        boolean online = true;
        boolean gone;
        SignedTextures textures;
        final List<SignedTextures> applied = new ArrayList<>();
        final List<Object> appliedSessions = new ArrayList<>();

        @Override
        public boolean usesAuthentication() {
            return online;
        }

        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public AccountSkinShareService.CurrentProfile current(UUID playerId, Object session) {
            return gone ? null : new AccountSkinShareService.CurrentProfile(textures);
        }

        @Override
        public int apply(UUID playerId, Object session, SignedTextures replacement) {
            if (gone) return -1;
            applied.add(replacement);
            appliedSessions.add(session);
            textures = replacement;
            return 1;
        }
    }
}
