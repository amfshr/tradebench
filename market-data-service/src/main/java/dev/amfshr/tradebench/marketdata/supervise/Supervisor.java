package dev.amfshr.tradebench.marketdata.supervise;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.jspecify.annotations.Nullable;

import dev.amfshr.tradebench.core.time.Clock;
import dev.amfshr.tradebench.ig.time.Sleeper;
import dev.amfshr.tradebench.marketdata.events.EventType;
import dev.amfshr.tradebench.marketdata.events.ServiceEvent;
import dev.amfshr.tradebench.marketdata.events.Severity;
import dev.amfshr.tradebench.marketdata.events.StreamState;
import dev.amfshr.tradebench.marketdata.store.EventLog;

/**
 * The resilience belt's shell (E1-T5 slice C): it composes the pure cores into a running service.
 * Threading mirrors the pump — the Lightstreamer callbacks ({@code onStatusChange}/
 * {@code onServerError}) do only cheap work (capture + enqueue), and the dedicated
 * {@code capture-supervisor} thread drains that queue, feeds the cores (so the cores stay
 * single-threaded, lock-free), emits {@link ServiceEvent}s, and runs the time-based checks each
 * sweep — executing remedies through {@link StreamControl}, paced by {@link BackoffPolicy}, until
 * recovery is given up ({@code onExhausted}): the recovery budget runs out, the rebuild ceiling
 * is hit, or the broker rejects the configuration outright. It covers connection resilience
 * (reconnect classification, stuck-substate escalation, backoff, exhaustion), the staleness
 * watchdog (quiet vs dead), witness quarantine (§3.5 blast radius), and the {@link BeltView} the
 * heartbeat reads.
 */
public final class Supervisor implements StreamObserver, BeltView, Runnable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Clock clock;
    private final EventLog events;
    private final StreamControl stream;
    private final MarketFreshness freshness;
    private final Runnable onExhausted;
    private final Sleeper sleeper;
    private final Duration sweepInterval;
    private final long giveUpAfterNanos;

    private final ReconnectClassifier reconnects;
    private final StuckSubstateEscalator stuck;
    private final StalenessWatchdog watchdog;
    private final WitnessQuarantine witness;
    private final BackoffPolicy backoff;
    private final AtomicLong eventWriteFailures = new AtomicLong();
    private final AtomicLong reconnectsTotal = new AtomicLong();
    private final Set<String> quarantinedMarkets = ConcurrentHashMap.newKeySet();
    // The heartbeat's view: written here on the sweep thread, read on the heartbeat thread.
    private volatile boolean streaming;
    private volatile boolean rebuilding;

    private final Queue<Observation> observations = new ConcurrentLinkedQueue<>();

    // Owned by the sweep thread only.
    private final Set<String> watched = new HashSet<>();
    private final Map<String, Long> lastFedTick = new HashMap<>();
    private final Map<String, Long> lastFedBar = new HashMap<>();
    // When each market's pair was last re-asked for: a remembered flag older than that is the old
    // subscription's and is not fed back (D29 (1)).
    private final Map<String, Long> flagClearedMono = new HashMap<>();
    // Rejections whose verdict waits for a witness (§3.5), kept for the verdict's record.
    private final Map<String, Observation.SubscriptionError> heldVerdicts = new HashMap<>();
    private int consecutiveFailures;
    private long lastRebuildMono = Long.MIN_VALUE;
    private long firstFailureMono = Long.MIN_VALUE;
    private boolean gaveUp;
    // A terminal disconnect, latched until a rebuild actually runs for it: the client gave up, so no
    // status will ever re-fire — the latch is what re-asks past the pacing floor (E1-T10 #2).
    private @Nullable Instant deadAt;
    private @Nullable ObjectNode deadDetail;
    private volatile boolean running = true;
    // The connection generation the sweep listens to: a superseded connection's word, enqueued
    // before its gate closed, is applied to nothing (E1-T10 #11).
    private int expectedGeneration;
    // A rebuild whose connect failed leaves no connection to report anything: the sweep asks
    // again, paced, until one connects — and only connected rebuilds count toward the ceiling, so
    // an unreachable broker ends by the budget, the clock, not the count (E1-T10 #18).
    private boolean streamDown;
    private @Nullable EventType downReason;
    private int rebuildsConnected;

    public Supervisor(Clock clock, Tuning tuning, DoubleSupplier jitter, EventLog events,
            StreamControl stream, MarketFreshness freshness, Runnable onExhausted, Sleeper sleeper,
            Duration sweepInterval) {
        this.clock = clock;
        this.events = events;
        this.stream = stream;
        this.freshness = freshness;
        this.onExhausted = onExhausted;
        this.sleeper = sleeper;
        this.sweepInterval = sweepInterval;
        this.giveUpAfterNanos = tuning.giveUpAfter().toNanos();
        this.reconnects = new ReconnectClassifier(tuning);
        this.stuck = new StuckSubstateEscalator(tuning);
        this.watchdog = new StalenessWatchdog(tuning, sweepInterval);
        this.witness = new WitnessQuarantine(tuning);
        this.backoff = new BackoffPolicy(tuning, jitter);
        this.expectedGeneration = stream.generation();
    }

    // --- Lightstreamer callback thread: cheap capture + enqueue only -------------------------

    @Override
    public void onStatusChange(int generation, String status) {
        observations.add(new Observation.Status(generation, status, clock.monotonicNanos(),
                clock.wallInstant().toEpochMilli()));
    }

    @Override
    public void onServerError(int generation, int code, String message) {
        observations.add(new Observation.ServerError(generation, code, message, clock.wallInstant()));
    }

    /** A market's PRICE or CHART leg confirmed subscribed (LS callback thread; §3.5 witness). */
    @Override
    public void onSubscribed(int generation, String epic, WitnessQuarantine.Kind kind) {
        observations.add(new Observation.Subscribed(generation, epic, kind));
    }

    /** A market's subscription was rejected (LS callback thread; §3.5 strike). */
    @Override
    public void onSubscriptionError(int generation, String epic, WitnessQuarantine.Kind kind, int code,
            String message) {
        observations.add(new Observation.SubscriptionError(generation, epic, kind, code, message,
                clock.monotonicNanos(), clock.wallInstant()));
    }

    /** Begin watching a market's freshness — called once per market at startup, before the sweep
     * thread exists (§3.4 per-market staleness, §3.5 witness subscribe-start); a rebuild re-arms
     * the survivors itself. */
    public void watch(String epic) {
        watched.add(epic);
        watchdog.track(epic, clock.monotonicNanos());
        witness.onSubscribeStarted(epic, clock.monotonicNanos());
        lastFedTick.put(epic, Long.MIN_VALUE);
        lastFedBar.put(epic, Long.MIN_VALUE);
    }

    /** Stop watching a market (e.g. once it is quarantined — slice C step 2b). */
    public void forget(String epic) {
        watched.remove(epic);
        watchdog.forget(epic);
        lastFedTick.remove(epic);
        lastFedBar.remove(epic);
        flagClearedMono.remove(epic);
    }

    // --- capture-supervisor thread ----------------------------------------------------------

    @Override
    public void run() {
        try {
            while (running) {
                sweep();
                sleeper.sleep(sweepInterval);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            die(e); // never a silent death: FEED_DEAD says why and the runner acts at once (#19)
            throw e; // and the thread's default handler still prints where
        }
    }

    public void stop() {
        running = false;
    }

    /** One pass: apply every pending observation, then the time-based checks — the recovery
     * budget first (it must not depend on how often a detector re-fires), then escalation. The
     * unit {@link #run()} repeats and the scenario harness drives directly. */
    public void sweep() {
        if (gaveUp) {
            return; // recovery has ended — the runner is taking over
        }
        Observation observation;
        while (!gaveUp && (observation = observations.poll()) != null) {
            if (observation.generation() != expectedGeneration) {
                continue; // a superseded connection's word, enqueued before its gate closed (#11)
            }
            if (observation instanceof Observation.Status status) {
                applyStatus(status);
            } else if (observation instanceof Observation.ServerError error) {
                ObjectNode detail = MAPPER.createObjectNode()
                        .put("code", error.code())
                        .put("message", error.message());
                record(ServiceEvent.of(EventType.IG_API_ERROR, error.at()).withDetail(detail));
                latchDeath(error.at(), detail.deepCopy());
            } else if (observation instanceof Observation.Subscribed subscribed) {
                witness.onSubscribed(subscribed.epic(), subscribed.kind());
            } else if (observation instanceof Observation.SubscriptionError error) {
                applyJudgment(witness.onSubscriptionError(error.epic(), error.monotonicNanos()), error);
            }
        }
        if (gaveUp) {
            return; // ended inside the drain: nothing after it may act (#13)
        }
        for (WitnessQuarantine.Judgment verdict : witness.rejudge(clock.monotonicNanos())) {
            String epic = epicOf(verdict);
            applyJudgment(verdict, Objects.requireNonNull(heldVerdicts.remove(epic),
                    () -> "no held rejection for " + epic)); // a witness confirmed, or its window lapsed
        }
        if (gaveUp) {
            return;
        }
        if (consecutiveFailures > 0
                && clock.monotonicNanos() - firstFailureMono >= giveUpAfterNanos) {
            giveUp("budget", clock.wallInstant());
            return;
        }
        if (deadAt != null && rebuild(EventType.CONNECTION_DEAD, deadAt, null, deadDetail)) {
            deadAt = null; // cleared only by a rebuild that ran — a paced-out one is asked again next sweep
            deadDetail = null;
        }
        if (streamDown && downReason != null) {
            rebuild(downReason, clock.wallInstant()); // the last attempt left no connection: ask again, paced
        }
        if (stuck.rebuildDue(clock.monotonicNanos())) {
            rebuild(EventType.STUCK_SUBSTATE_ESCALATED, clock.wallInstant());
        }
        if (gaveUp) {
            return;
        }
        checkStaleness();
    }

    /** The first signal dates the death; a second announcement of the same death changes nothing
     * (playbook §3.1: the first {@code onServerError} is a death, a bare DISCONNECTED is a death). */
    private void latchDeath(Instant at, ObjectNode detail) {
        if (deadAt == null) {
            deadAt = at;
            deadDetail = detail;
        }
    }

    /** Sample each watched market's freshness, feed the watchdog on-change, and execute its
     * remedies — resubscribe the sick market, or (session-shaped) a backoff-paced rebuild. */
    private void checkStaleness() {
        long now = clock.monotonicNanos();
        long wallMillis = clock.wallInstant().toEpochMilli();
        for (String epic : watched) {
            long tick = freshness.lastTickMono(epic);
            if (tick != Long.MIN_VALUE && tick != lastFedTick.get(epic)) {
                watchdog.onTick(epic, tick); // only on advance — a replayed last-seen must not heal
                lastFedTick.put(epic, tick);
            }
            long bar = freshness.lastBarMono(epic);
            if (bar != Long.MIN_VALUE && bar != lastFedBar.get(epic)) {
                watchdog.onSealedBar(epic, bar);
                lastFedBar.put(epic, bar);
            }
            String flag = freshness.dealFlag(epic);
            Long cleared = flagClearedMono.get(epic);
            if (flag != null && (cleared == null || tick > cleared)) {
                watchdog.onDealFlag(epic, flag, now); // the flag rides the tick: only a tick after the re-ask is fresh
            }
        }
        for (StalenessWatchdog.Remedy remedy : watchdog.evaluate(now, wallMillis)) {
            applyRemedy(remedy, clock.wallInstant());
        }
    }

    private void applyRemedy(StalenessWatchdog.Remedy remedy, Instant occurredAt) {
        if (remedy.action() == StalenessWatchdog.Action.RESUBSCRIBE) {
            record(ServiceEvent.of(EventType.WATCHDOG_STALE, occurredAt).forEpic(remedy.epic())
                    .withDetail(MAPPER.createObjectNode()
                            .put("action", "resubscribe")
                            .put("signal", remedy.signal().name())));
            reasking(remedy.epic());
            witness.onSubscribeStarted(remedy.epic(), clock.monotonicNanos()); // a fresh pair, a fresh confirm window
            stream.resubscribe(remedy.epic());
        } else {
            rebuild(EventType.WATCHDOG_STALE, occurredAt); // backoff-paced session rebuild
        }
    }

    /** A market's pair is being re-asked for: its remembered flag belongs to the old subscription. */
    private void reasking(String epic) {
        watchdog.clearDealFlag(epic);
        flagClearedMono.put(epic, clock.monotonicNanos());
    }

    /** Execute a subscription-failure {@link WitnessQuarantine.Judgment} — the §3.5 blast-radius
     * decision. Retry re-attempts the failing pair in place; Wait holds the rejection until its
     * witness confirms or its window lapses, and the sweep re-asks every round; Quarantine isolates
     * one market; Rebuild treats the failure as session-shaped; Ignored is a rejection with nothing
     * left to judge. A verdict is dated when it is rendered; its record names the rejection. */
    private void applyJudgment(WitnessQuarantine.Judgment judgment, Observation.SubscriptionError error) {
        switch (judgment) {
            case WitnessQuarantine.Judgment.Retry(String epic) -> {
                witness.onSubscribeStarted(epic, clock.monotonicNanos());
                reasking(epic);
                stream.resubscribe(epic); // surgical re-attempt of the failing pair
            }
            case WitnessQuarantine.Judgment.Wait() -> heldVerdicts.put(error.epic(), error);
            case WitnessQuarantine.Judgment.Ignored _ -> {
                // quarantined, or a leg of an attempt already struck or replaced; nothing to do
            }
            case WitnessQuarantine.Judgment.Quarantine(String epic) -> quarantineMarket(epic, error);
            case WitnessQuarantine.Judgment.Rebuild(String epic) -> rebuild(EventType.SUBSCRIPTION_REJECTED,
                    clock.wallInstant(), epic, rejection(error));
        }
    }

    private static String epicOf(WitnessQuarantine.Judgment verdict) {
        return switch (verdict) {
            case WitnessQuarantine.Judgment.Quarantine(String epic) -> epic;
            case WitnessQuarantine.Judgment.Rebuild(String epic) -> epic;
            default -> throw new IllegalStateException("not a rendered verdict: " + verdict);
        };
    }

    private static ObjectNode rejection(Observation.SubscriptionError error) {
        return MAPPER.createObjectNode()
                .put("code", error.code())
                .put("message", error.message())
                .put("leg", error.kind().name())
                .put("rejectedAt", error.at().toString());
    }

    /** Isolate one provably market-shaped failure: record it, stop watching it, and unsubscribe its
     * pair. A refused unsubscribe double-delivers, so it escalates to a session rebuild (§3.5). */
    private void quarantineMarket(String epic, Observation.SubscriptionError error) {
        record(ServiceEvent.of(EventType.MARKET_QUARANTINED, clock.wallInstant()).forEpic(epic)
                .withDetail(rejection(error)));
        forget(epic);
        quarantinedMarkets.add(epic);
        if (!stream.quarantine(epic)) {
            applyJudgment(witness.onUnsubscribeRefused(epic), error);
        }
    }

    private void applyStatus(Observation.Status status) {
        if (reconnects.noteFor(status.status()) == ReconnectClassifier.Note.TRANSPORT_DOWNGRADED) {
            record(ServiceEvent.of(EventType.TRANSPORT_DOWNGRADED,
                    Instant.ofEpochMilli(status.wallMillis())));
        }
        ReconnectClassifier.Reconnect reconnect =
                reconnects.onStatus(status.status(), status.monotonicNanos(), status.wallMillis());
        if (reconnect != null) {
            reconnectsTotal.incrementAndGet();
            consecutiveFailures = 0; // streaming resumed — the ladder resets; the next outage's
            lastRebuildMono = Long.MIN_VALUE; // first rebuild restarts the budget clock
            rebuildsConnected = 0;
            streamDown = false;
            downReason = null;
            record(new ServiceEvent(EventType.RECONNECT,
                    reconnect.replayed() ? Severity.INFO : Severity.WARN, null,
                    Instant.ofEpochMilli(status.wallMillis()), null,
                    MAPPER.createObjectNode()
                            .put("replayed", reconnect.replayed())
                            .put("wallOutageMs", reconnect.wallOutage().toMillis())
                            .put("awakeOutageMs", reconnect.awakeOutage().toMillis())
                            .put("hostSlept", reconnect.hostSleptDuring())));
        }
        stuck.onStatus(status.status(), status.monotonicNanos());
        streaming = ReconnectClassifier.isStreaming(status.status());
        watchdog.onConnection(streaming, status.monotonicNanos()); // down: no verdicts; up: fresh windows
        if (streaming) {
            rebuilding = false;
        }
        if (ReconnectClassifier.isTerminal(status.status())) {
            // The client gave up: no retry substate will ever escalate this, and the watchdog stands
            // down on closed markets — so the rebuild is ours to ask for, and the sweep keeps asking.
            latchDeath(Instant.ofEpochMilli(status.wallMillis()),
                    MAPPER.createObjectNode().put("status", status.status()));
        }
    }

    /** Execute a session rebuild, paced by backoff; give up loud when the ladder is exhausted.
     * Returns false only when the request was paced out — ask again next sweep. */
    private boolean rebuild(EventType reason, Instant occurredAt) {
        return rebuild(reason, occurredAt, null, null);
    }

    /** As above, but scoping the reason event to {@code epic} with {@code detail} — used when one
     * market's rejection with no healthy witness is what forced the whole-session rebuild (§3.5).
     * The reason event is written only when the rebuild actually proceeds (never when paced out or
     * exhausted), so it stays a faithful record of rebuilds that happened. */
    private boolean rebuild(EventType reason, Instant occurredAt, @Nullable String epic,
            @Nullable ObjectNode detail) {
        if (gaveUp) {
            return true; // nothing left to ask for
        }
        long now = clock.monotonicNanos();
        if (consecutiveFailures > 0
                && now - lastRebuildMono < backoff.delayFor(consecutiveFailures).toNanos()) {
            return false; // not yet — space rebuilds so a re-login storm never trips IG's throttles (§3.2)
        }
        if (backoff.exhausted(rebuildsConnected)) {
            giveUp("ceiling", occurredAt); // ten connected and none streamed — the cap behind the budget
            return true;
        }
        if (consecutiveFailures == 0) {
            firstFailureMono = now; // the recovery budget runs from an outage's first rebuild
        }
        consecutiveFailures++;
        lastRebuildMono = now;
        ServiceEvent reasonEvent = ServiceEvent.of(reason, occurredAt);
        if (epic != null) {
            reasonEvent = reasonEvent.forEpic(epic);
        }
        if (detail != null) {
            reasonEvent = reasonEvent.withDetail(detail);
        }
        record(reasonEvent);
        reconnects.rebuilding(now, clock.wallInstant().toEpochMilli()); // the outage opens now, on both clocks (#21)
        rebuilding = true; // the view reads RECONNECTING until the new connection streams
        watchdog.onConnection(false, now); // and the watchdog waits for it
        StreamControl.Outcome outcome = stream.rebuild();
        stuck.reset(); // the old connection's substate died with it; a new one reports its own (#18)
        expectedGeneration = stream.generation(); // whatever the old one still says is nobody's (#11)
        witness.onSessionRebuilt(); // strikes and held verdicts reset; quarantine persists (exit is restart-only)
        heldVerdicts.clear();
        switch (outcome) {
            case FATAL -> {
                giveUp("fatal_config", occurredAt); // never climb a ladder against a lockout
                return true;
            }
            case FAILED -> {
                streamDown = true; // no connection to report anything: the sweep asks again, paced (#18)
                downReason = reason;
                return true;
            }
            case CONNECTED -> {
                streamDown = false;
                downReason = null;
                rebuildsConnected++;
            }
        }
        // Re-read the clock: a paced re-login blocks ~61s, and the §3.5 confirm window must start
        // when the new session's subscribes begin, not when the rebuild was decided.
        long resubscribed = clock.monotonicNanos();
        for (String survivor : watched) {
            witness.onSubscribeStarted(survivor, resubscribed); // the survivors re-subscribe now
            reasking(survivor);
        }
        return true;
    }

    /** End recovery loud, exactly once: {@code FEED_DEAD} says why, sweeping stops, and the
     * runner takes over (in {@code Main}, an orderly exit for the process supervisor to restart). */
    private void giveUp(String reason, Instant occurredAt) {
        giveUp(reason, occurredAt, null);
    }

    private void giveUp(String reason, Instant occurredAt, @Nullable String cause) {
        if (gaveUp) {
            return; // once — a second signal of the same end changes nothing (#13)
        }
        gaveUp = true;
        running = false;
        ObjectNode detail = MAPPER.createObjectNode().put("reason", reason);
        if (cause != null) {
            detail.put("cause", cause);
        }
        record(ServiceEvent.of(EventType.FEED_DEAD, occurredAt).withDetail(detail));
        onExhausted.run();
    }

    /** A sweep that threw: recovery ends loud — {@code FEED_DEAD{reason: fatal, cause}} and the
     * runner acts at once — never a silent dead thread found a heartbeat later (#19). {@link #run()}
     * does this on the sweep thread; the scenario harness calls it in run()'s place. */
    public void die(RuntimeException cause) {
        giveUp("fatal", clock.wallInstant(), String.valueOf(cause));
    }

    /** Event writes that failed and were swallowed — the belt never dies for a breadcrumb (the
     * pump ruling, applied here too); nonzero is the alarm, surfaced by the heartbeat. */
    public long eventWriteFailures() {
        return eventWriteFailures.get();
    }

    // Observability is downstream of the decision: a failing write is counted, never allowed to
    // kill the sweep thread — and giveUp() must always reach onExhausted.
    private void record(ServiceEvent event) {
        try {
            events.write(event);
        } catch (RuntimeException e) {
            eventWriteFailures.incrementAndGet();
        }
    }

    // --- the heartbeat's read-only view --------------------------------------------------------

    @Override
    public StreamState stateOf(String epic) {
        if (quarantinedMarkets.contains(epic)) {
            return StreamState.QUARANTINED;
        }
        return streaming && !rebuilding ? StreamState.CONNECTED_STREAMING : StreamState.RECONNECTING;
    }

    @Override
    public long reconnectsTotal() {
        return reconnectsTotal.get();
    }

    private sealed interface Observation {
        int generation();

        record Status(int generation, String status, long monotonicNanos, long wallMillis) implements Observation {
        }

        record ServerError(int generation, int code, String message, Instant at) implements Observation {
        }

        record Subscribed(int generation, String epic, WitnessQuarantine.Kind kind) implements Observation {
        }

        record SubscriptionError(int generation, String epic, WitnessQuarantine.Kind kind, int code,
                String message, long monotonicNanos, Instant at) implements Observation {
        }
    }
}
