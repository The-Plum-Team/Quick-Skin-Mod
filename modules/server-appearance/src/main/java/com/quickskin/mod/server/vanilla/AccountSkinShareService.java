package com.quickskin.mod.server.vanilla;

import com.quickskin.mod.config.ServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Shows a Quick Skin player's new Mojang account skin to players who do not run Quick Skin.
 *
 * <p>Unmodded clients render only a {@code textures} property that Mojang signed. After a Quick
 * Skin client reports that its player uploaded a skin to their own account, this service asks
 * Mojang's session server for that profile's freshly signed textures and hands them to the
 * {@link Target}, which replaces the property and refreshes the player for unmodded observers.
 * Nothing here ever uploads a skin or calls a third-party service.</p>
 *
 * <p>Every lookup runs on one bounded daemon worker, never on the server thread. Lookups are
 * paced by a server-wide token bucket, a player starts at most one round per interval, a report
 * that arrives during a round re-arms one more round, and a round polls with backoff until the
 * session server reports a different appearance or its attempts are spent. The option is off by
 * default and does nothing on an offline-mode server, where Mojang never signed the profile.</p>
 */
public final class AccountSkinShareService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(AccountSkinShareService.class);
    private static final AccountSkinShareService INSTANCE = new AccountSkinShareService(
            Policy.DEFAULT,
            new MojangSessionProfileFetcher(),
            () -> System.nanoTime() / 1_000_000L,
            () -> ServerConfig.getInstance().shareAccountSkinWithVanillaClients,
            AccountSkinShareService::daemonScheduler,
            (playerId, outcome) -> { });

    private final Policy policy;
    private final SessionProfileFetcher fetcher;
    private final LongSupplier clockMillis;
    private final BooleanSupplier enabled;
    private final SchedulerFactory schedulerFactory;
    private final BiConsumer<UUID, Outcome> outcomeListener;
    private final Map<UUID, Job> jobs = new HashMap<>();

    private Scheduler scheduler;
    private Target target;
    private double tokens;
    private long lastRefillMillis;

    public AccountSkinShareService(
            Policy policy,
            SessionProfileFetcher fetcher,
            LongSupplier clockMillis,
            BooleanSupplier enabled,
            SchedulerFactory schedulerFactory,
            BiConsumer<UUID, Outcome> outcomeListener) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.enabled = Objects.requireNonNull(enabled, "enabled");
        this.schedulerFactory = Objects.requireNonNull(schedulerFactory, "schedulerFactory");
        this.outcomeListener = Objects.requireNonNull(outcomeListener, "outcomeListener");
    }

    public static AccountSkinShareService getInstance() {
        return INSTANCE;
    }

    /** Binds the service to one running server; any earlier runtime is discarded first. */
    public synchronized void start(Target target) {
        Objects.requireNonNull(target, "target");
        closeLocked();
        this.target = target;
        this.scheduler = Objects.requireNonNull(schedulerFactory.create(), "scheduler");
        this.tokens = policy.bucketCapacity();
        this.lastRefillMillis = clockMillis.getAsLong();
    }

    /**
     * Records that the player of this exact session changed its Mojang account skin.
     * Cheap and non-blocking; the lookup itself happens later on the worker.
     */
    public synchronized Admission request(UUID playerId, Object session) {
        if (playerId == null || session == null) return Admission.INVALID;
        if (scheduler == null || target == null) return Admission.NOT_RUNNING;
        if (!enabled.getAsBoolean()) return Admission.DISABLED;
        if (!target.usesAuthentication()) return Admission.OFFLINE_MODE;

        long now = clockMillis.getAsLong();
        Job job = jobs.get(playerId);
        if (job != null && job.session != session) {
            removeLocked(job);
            job = null;
        }
        if (job != null) {
            if (job.state != State.COOLING) {
                job.rearm = true;
                return Admission.COALESCED;
            }
            startRoundLocked(job, Math.max(
                    policy.firstAttemptDelayMillis(),
                    job.roundStartedMillis + policy.minimumRoundIntervalMillis() - now));
            return Admission.ACCEPTED;
        }
        if (jobs.size() >= policy.maxTrackedPlayers()) return Admission.CAPACITY;
        job = new Job(playerId, session);
        jobs.put(playerId, job);
        startRoundLocked(job, policy.firstAttemptDelayMillis());
        return Admission.ACCEPTED;
    }

    /** Forgets the exact session; a replacement connection of the same player is untouched. */
    public synchronized void playerDisconnected(UUID playerId, Object session) {
        if (playerId == null || session == null) return;
        Job job = jobs.get(playerId);
        if (job != null && job.session == session) removeLocked(job);
    }

    /** Number of players with a scheduled, running or cooling round. */
    public synchronized int trackedPlayers() {
        return jobs.size();
    }

    @Override
    public synchronized void close() {
        closeLocked();
    }

    private void closeLocked() {
        for (Job job : new ArrayList<>(jobs.values())) removeLocked(job);
        jobs.clear();
        if (scheduler != null) {
            try {
                scheduler.shutdown();
            } catch (RuntimeException error) {
                LOGGER.warn("Could not stop the Quick Skin account skin sharing worker", error);
            }
        }
        scheduler = null;
        target = null;
    }

    private void startRoundLocked(Job job, long delayMillis) {
        long delay = Math.max(0L, delayMillis);
        job.state = State.SCHEDULED;
        job.attempts = 0;
        job.rearm = false;
        job.roundStartedMillis = clockMillis.getAsLong() + delay;
        scheduleLocked(job, delay, () -> runAttempt(job));
    }

    private void scheduleLocked(Job job, long delayMillis, Runnable task) {
        if (job.pending != null) job.pending.cancel();
        job.pending = scheduler.schedule(task, Math.max(0L, delayMillis));
    }

    private void removeLocked(Job job) {
        if (job.pending != null) job.pending.cancel();
        job.pending = null;
        job.state = State.REMOVED;
        jobs.remove(job.playerId, job);
    }

    private boolean isCurrentLocked(Job job) {
        return scheduler != null && jobs.get(job.playerId) == job && job.state != State.REMOVED;
    }

    /** Worker thread: one session-server lookup. */
    private void runAttempt(Job job) {
        synchronized (this) {
            if (!isCurrentLocked(job) || job.state != State.SCHEDULED) return;
            long wait = takeTokenLocked();
            if (wait > 0L) {
                scheduleLocked(job, wait, () -> runAttempt(job));
                return;
            }
            job.pending = null;
            job.state = State.FETCHING;
            job.attempts++;
        }

        SessionProfileFetcher.Response response;
        try {
            response = fetcher.fetch(job.playerId);
        } catch (IOException | RuntimeException error) {
            LOGGER.debug("Session server lookup for {} failed", job.playerId, error);
            retry(job, 0L, false);
            return;
        }
        int status = response.statusCode();
        if (status == 200) {
            SessionProfile profile;
            try {
                profile = SessionProfile.parse(response.body(), job.playerId);
            } catch (IllegalArgumentException error) {
                LOGGER.warn("Ignoring an invalid session server profile for {}: {}",
                        job.playerId, error.getMessage());
                finish(job, Outcome.REJECTED);
                return;
            }
            Target activeTarget;
            synchronized (this) {
                if (!isCurrentLocked(job)) return;
                job.state = State.APPLYING;
                activeTarget = target;
            }
            try {
                activeTarget.execute(() -> compareAndApply(job, activeTarget, profile));
            } catch (RuntimeException error) {
                LOGGER.debug("Could not hand the refreshed profile of {} to the server", job.playerId, error);
                finish(job, Outcome.SESSION_GONE);
            }
        } else if (status == 204 || status == 404) {
            finish(job, Outcome.NO_PROFILE);
        } else if (status == 429) {
            retry(job, response.retryAfterMillis(), false);
        } else if (status >= 500 && status <= 599) {
            retry(job, response.retryAfterMillis(), false);
        } else {
            LOGGER.warn("The session server answered {} for {}; not sharing the account skin",
                    status, job.playerId);
            finish(job, Outcome.REJECTED);
        }
    }

    /** Server thread: replace the profile only when Mojang already reports the new appearance. */
    private void compareAndApply(Job job, Target activeTarget, SessionProfile profile) {
        synchronized (this) {
            if (!isCurrentLocked(job) || job.state != State.APPLYING) return;
        }
        CurrentProfile current;
        try {
            current = activeTarget.current(job.playerId, job.session);
        } catch (RuntimeException error) {
            LOGGER.warn("Could not read the profile of {}", job.playerId, error);
            finish(job, Outcome.SESSION_GONE);
            return;
        }
        if (current == null) {
            finish(job, Outcome.SESSION_GONE);
            return;
        }
        if (profile.appearance().equals(appearanceOf(current.textures(), job.playerId))) {
            retry(job, 0L, true);
            return;
        }
        int refreshed;
        try {
            refreshed = activeTarget.apply(job.playerId, job.session, profile.textures());
        } catch (RuntimeException error) {
            LOGGER.warn("Could not refresh the vanilla profile of {}", job.playerId, error);
            finish(job, Outcome.REJECTED);
            return;
        }
        if (refreshed < 0) {
            finish(job, Outcome.SESSION_GONE);
            return;
        }
        LOGGER.info("Shared the new Mojang account skin of {} with {} player(s) without Quick Skin",
                job.playerId, refreshed);
        finish(job, Outcome.APPLIED);
    }

    private static AccountTextures appearanceOf(SignedTextures textures, UUID playerId) {
        if (textures == null) return null;
        try {
            return AccountTextures.decode(textures.value(), playerId);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void retry(Job job, long minimumDelayMillis, boolean unchanged) {
        Outcome exhausted = null;
        synchronized (this) {
            if (!isCurrentLocked(job)) return;
            List<Long> delays = policy.retryDelaysMillis();
            if (job.attempts > delays.size()) {
                exhausted = unchanged ? Outcome.UNCHANGED : Outcome.GAVE_UP;
            } else {
                long delay = Math.max(delays.get(job.attempts - 1), minimumDelayMillis);
                job.state = State.SCHEDULED;
                scheduleLocked(job, delay, () -> runAttempt(job));
            }
        }
        if (exhausted != null) finish(job, exhausted);
    }

    private void finish(Job job, Outcome outcome) {
        synchronized (this) {
            if (!isCurrentLocked(job)) return;
            if (job.rearm) {
                startRoundLocked(job, Math.max(
                        policy.firstAttemptDelayMillis(),
                        job.roundStartedMillis + policy.minimumRoundIntervalMillis()
                                - clockMillis.getAsLong()));
            } else {
                job.state = State.COOLING;
                long until = job.roundStartedMillis + policy.minimumRoundIntervalMillis();
                scheduleLocked(job, until - clockMillis.getAsLong(), () -> expire(job));
            }
        }
        switch (outcome) {
            case UNCHANGED -> LOGGER.info(
                    "Mojang still reports the previous skin of {}; players without Quick Skin keep "
                            + "it until the player rejoins", job.playerId);
            case NO_PROFILE -> LOGGER.warn(
                    "Mojang has no profile {}; its account skin cannot be shared", job.playerId);
            case GAVE_UP -> LOGGER.warn(
                    "Gave up reading the new account skin of {} from Mojang", job.playerId);
            default -> { }
        }
        try {
            outcomeListener.accept(job.playerId, outcome);
        } catch (RuntimeException error) {
            LOGGER.debug("Account skin sharing listener failed", error);
        }
    }

    private synchronized void expire(Job job) {
        if (isCurrentLocked(job) && job.state == State.COOLING) removeLocked(job);
    }

    /** Returns 0 after taking a lookup token, otherwise the milliseconds until one is available. */
    private long takeTokenLocked() {
        long now = clockMillis.getAsLong();
        long elapsed = Math.max(0L, now - lastRefillMillis);
        lastRefillMillis = now;
        tokens = Math.min(policy.bucketCapacity(),
                tokens + (double) elapsed / policy.refillIntervalMillis());
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return 0L;
        }
        return Math.max(1L, (long) Math.ceil((1.0 - tokens) * policy.refillIntervalMillis()));
    }

    private static Scheduler daemonScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "QuickSkin-AccountSkinShare");
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, error) ->
                    LOGGER.error("Uncaught Quick Skin account skin sharing failure", error));
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return new Scheduler() {
            @Override
            public ScheduledTask schedule(Runnable task, long delayMillis) {
                ScheduledFuture<?> future = executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
                return () -> future.cancel(false);
            }

            @Override
            public void shutdown() {
                executor.shutdownNow();
            }
        };
    }

    /** Result of {@link #request}. */
    public enum Admission {
        ACCEPTED,
        COALESCED,
        DISABLED,
        OFFLINE_MODE,
        NOT_RUNNING,
        CAPACITY,
        INVALID
    }

    /** How one round ended. */
    public enum Outcome {
        APPLIED,
        UNCHANGED,
        NO_PROFILE,
        REJECTED,
        GAVE_UP,
        SESSION_GONE
    }

    /** The running server, implemented by the composition root. */
    public interface Target {
        /** Whether the server authenticates players with Mojang (online mode). */
        boolean usesAuthentication();

        /** Runs the task on the server thread. */
        void execute(Runnable task);

        /** Server thread: the exact session's current profile, or {@code null} when it left. */
        CurrentProfile current(UUID playerId, Object session);

        /**
         * Server thread: replaces the exact session's textures and refreshes it for observers
         * without Quick Skin.
         *
         * @return the number of refreshed observers, or a negative value when the session left
         */
        int apply(UUID playerId, Object session, SignedTextures textures);
    }

    /** The textures a session currently carries; {@code textures} is null when it has none. */
    public record CurrentProfile(SignedTextures textures) {
    }

    /** Delayed execution on the sharing worker. */
    public interface Scheduler {
        ScheduledTask schedule(Runnable task, long delayMillis);

        void shutdown();
    }

    public interface ScheduledTask {
        void cancel();
    }

    @FunctionalInterface
    public interface SchedulerFactory {
        Scheduler create();
    }

    /**
     * Pacing. The defaults allow one lookup every two seconds server-wide with a burst of ten,
     * one round per player every thirty seconds, and six lookups per round over about two minutes.
     */
    public record Policy(
            long firstAttemptDelayMillis,
            List<Long> retryDelaysMillis,
            long minimumRoundIntervalMillis,
            int bucketCapacity,
            long refillIntervalMillis,
            int maxTrackedPlayers) {
        public static final Policy DEFAULT = new Policy(
                2_000L,
                List.of(5_000L, 10_000L, 20_000L, 30_000L, 60_000L),
                30_000L,
                10,
                2_000L,
                128);

        public Policy {
            retryDelaysMillis = List.copyOf(retryDelaysMillis);
            if (firstAttemptDelayMillis < 0L || minimumRoundIntervalMillis < 0L
                    || bucketCapacity < 1 || refillIntervalMillis < 1L || maxTrackedPlayers < 1
                    || retryDelaysMillis.stream().anyMatch(delay -> delay == null || delay < 0L)) {
                throw new IllegalArgumentException("Invalid account skin sharing policy");
            }
        }
    }

    private enum State {
        SCHEDULED,
        FETCHING,
        APPLYING,
        COOLING,
        REMOVED
    }

    private static final class Job {
        final UUID playerId;
        final Object session;
        State state = State.SCHEDULED;
        int attempts;
        boolean rearm;
        long roundStartedMillis;
        ScheduledTask pending;

        Job(UUID playerId, Object session) {
            this.playerId = playerId;
            this.session = session;
        }
    }
}
