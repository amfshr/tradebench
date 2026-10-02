package dev.amfshr.tradebench.marketdata.supervise;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.DoubleSupplier;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.amfshr.tradebench.core.time.Clock;
import dev.amfshr.tradebench.ig.stream.StreamTransport;
import dev.amfshr.tradebench.ig.time.Sleeper;
import dev.amfshr.tradebench.marketdata.events.EventType;
import dev.amfshr.tradebench.marketdata.events.ServiceEvent;
import dev.amfshr.tradebench.marketdata.events.Severity;
import dev.amfshr.tradebench.marketdata.store.EventLog;

/**
 * The resilience belt's shell (E1-T5 slice C): it composes the pure cores into a running service.
 * Threading mirrors the pump — the Lightstreamer callbacks ({@code onStatusChange}/
 * {@code onServerError}) do only cheap work (capture + enqueue), and the dedicated
 * {@code capture-supervisor} thread drains that queue, feeds the cores (so the cores stay
 * single-threaded, lock-free), emits {@link ServiceEvent}s, and runs the time-based checks each
 * sweep — executing remedies through {@link StreamControl}, paced by {@link BackoffPolicy}, until
 * recovery is exhausted ({@code onExhausted}). <b>Slice C step 2a</b>: connection resilience
 * (reconnect classification + stuck-substate escalation + backoff + exhaustion); the staleness
 * watchdog and witness quarantine land in 2b.
 */
public final class Supervisor implements StreamTransport.ConnectionListener, Runnable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Clock clock;
    private final EventLog events;
    private final StreamControl stream;
    private final MarketFreshness freshness;
    private final Runnable onExhausted;
    private final Sleeper sleeper;
    private final Duration sweepInterval;

    private final ReconnectClassifier reconnects;
    private final StuckSubstateEscalator stuck;
    private final StalenessWatchdog watchdog;
    private final BackoffPolicy backoff;

    private final Queue<Observation> observations = new ConcurrentLinkedQueue<>();

    // Owned by the sweep thread only.
    private final Set<String> watched = new HashSet<>();
    private final Map<String, Long> lastFedTick = new HashMap<>();
    private final Map<String, Long> lastFedBar = new HashMap<>();
    private int consecutiveFailures;
    private long lastRebuildMono = Long.MIN_VALUE;
    private volatile boolean running = true;

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
        this.reconnects = new ReconnectClassifier(tuning);
        this.stuck = new StuckSubstateEscalator(tuning);
        this.watchdog = new StalenessWatchdog(tuning);
        this.backoff = new BackoffPolicy(tuning, jitter);
    }

    // --- Lightstreamer callback thread: cheap capture + enqueue only -------------------------

    @Override
    public void onStatusChange(String status) {
        observations.add(new Observation.Status(status, clock.monotonicNanos(),
                clock.wallInstant().toEpochMilli()));
    }

    @Override
    public void onServerError(int code, String message) {
        observations.add(new Observation.ServerError(code, message, clock.wallInstant()));
    }

    /** Hush the farewell DISCONNECTED before an intentional close (§3.6). */
    public void closing() {
        reconnects.closing();
    }

    /** Begin watching a market's freshness — called as it is subscribed (startup or on the sweep
     * thread during a rebuild; §3.4 per-market staleness). */
    public void watch(String epic) {
        watched.add(epic);
        watchdog.track(epic, clock.monotonicNanos());
        lastFedTick.put(epic, Long.MIN_VALUE);
        lastFedBar.put(epic, Long.MIN_VALUE);
    }

    /** Stop watching a market (e.g. once it is quarantined — slice C step 2b). */
    public void forget(String epic) {
        watched.remove(epic);
        watchdog.forget(epic);
        lastFedTick.remove(epic);
        lastFedBar.remove(epic);
    }

    // --- capture-supervisor thread ----------------------------------------------------------

    @Override
    public void run() {
        while (running) {
            sweep();
            try {
                sleeper.sleep(sweepInterval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public void stop() {
        running = false;
    }

    /** One pass: apply every pending observation, then the time-based escalation check. */
    void sweep() {
        Observation observation;
        while ((observation = observations.poll()) != null) {
            if (observation instanceof Observation.Status status) {
                applyStatus(status);
            } else if (observation instanceof Observation.ServerError(int code, String message, Instant at)) {
                events.write(ServiceEvent.of(EventType.IG_API_ERROR, at)
                        .withDetail(MAPPER.createObjectNode()
                                .put("code", code)
                                .put("message", message)));
            }
        }
        if (stuck.rebuildDue(clock.monotonicNanos())) {
            rebuild(EventType.STUCK_SUBSTATE_ESCALATED, clock.wallInstant());
        }
        checkStaleness();
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
            if (flag != null) {
                watchdog.onDealFlag(epic, flag);
            }
        }
        for (StalenessWatchdog.Remedy remedy : watchdog.evaluate(now, wallMillis)) {
            applyRemedy(remedy, clock.wallInstant());
        }
    }

    private void applyRemedy(StalenessWatchdog.Remedy remedy, Instant occurredAt) {
        if (remedy.action() == StalenessWatchdog.Action.RESUBSCRIBE) {
            events.write(ServiceEvent.of(EventType.WATCHDOG_STALE, occurredAt).forEpic(remedy.epic())
                    .withDetail(MAPPER.createObjectNode()
                            .put("action", "resubscribe")
                            .put("signal", remedy.signal().name())));
            stream.resubscribe(remedy.epic());
        } else {
            rebuild(EventType.WATCHDOG_STALE, occurredAt); // backoff-paced session rebuild
        }
    }

    private void applyStatus(Observation.Status status) {
        if (reconnects.noteFor(status.status()) == ReconnectClassifier.Note.TRANSPORT_DOWNGRADED) {
            events.write(ServiceEvent.of(EventType.TRANSPORT_DOWNGRADED,
                    Instant.ofEpochMilli(status.wallMillis())));
        }
        // A GRACEFUL_CLOSE note is intentionally silent — that is the hush (§3.6).
        ReconnectClassifier.Reconnect reconnect =
                reconnects.onStatus(status.status(), status.monotonicNanos(), status.wallMillis());
        if (reconnect != null) {
            consecutiveFailures = 0; // streaming resumed — the backoff ladder resets
            lastRebuildMono = Long.MIN_VALUE;
            events.write(new ServiceEvent(EventType.RECONNECT,
                    reconnect.replayed() ? Severity.INFO : Severity.WARN, null,
                    Instant.ofEpochMilli(status.wallMillis()), null,
                    MAPPER.createObjectNode()
                            .put("replayed", reconnect.replayed())
                            .put("wallOutageMs", reconnect.wallOutage().toMillis())
                            .put("awakeOutageMs", reconnect.awakeOutage().toMillis())
                            .put("hostSlept", reconnect.hostSleptDuring())));
        }
        stuck.onStatus(status.status(), status.monotonicNanos());
    }

    /** Execute a session rebuild, paced by backoff; give up loud when the ladder is exhausted. */
    private void rebuild(EventType reason, Instant occurredAt) {
        long now = clock.monotonicNanos();
        if (consecutiveFailures > 0
                && now - lastRebuildMono < backoff.delayFor(consecutiveFailures).toNanos()) {
            return; // not yet — space rebuilds so a re-login storm never trips IG's throttles (§3.2)
        }
        if (backoff.exhausted(consecutiveFailures)) {
            events.write(ServiceEvent.of(EventType.FEED_DEAD, occurredAt));
            onExhausted.run();
            return;
        }
        consecutiveFailures++;
        lastRebuildMono = now;
        events.write(ServiceEvent.of(reason, occurredAt));
        stream.rebuild();
    }

    private sealed interface Observation {
        record Status(String status, long monotonicNanos, long wallMillis) implements Observation {
        }

        record ServerError(int code, String message, Instant at) implements Observation {
        }
    }
}
