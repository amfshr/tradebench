package dev.amfshr.tradebench.marketdata.ingest;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.amfshr.tradebench.core.domain.Bar1m;
import dev.amfshr.tradebench.core.domain.Tick;
import dev.amfshr.tradebench.core.time.Clock;
import dev.amfshr.tradebench.ig.time.Sleeper;
import dev.amfshr.tradebench.marketdata.coverage.GapDetector;
import dev.amfshr.tradebench.marketdata.events.EventType;
import dev.amfshr.tradebench.marketdata.events.ServiceEvent;
import dev.amfshr.tradebench.marketdata.store.CaptureStore;
import dev.amfshr.tradebench.marketdata.store.EventLog;
import dev.amfshr.tradebench.marketdata.store.GapStore;
import dev.amfshr.tradebench.marketdata.store.PersistenceException;
import dev.amfshr.tradebench.marketdata.supervise.BackoffPolicy;

/**
 * Single consumer of the capture queues. Bars drain first (the must-persist backbone) straight
 * to the sink. A <b>retryable</b> sink failure ({@link PersistenceException#retryable()} — the
 * blip taxonomy) <b>holds</b>: bars stay queued (ack-after-apply), the store keeps its tick
 * batch, and the pump backs off, asks the sink to {@link CaptureStore#recover() recover}, and
 * resumes — loud ({@link #sinkFailures()}, the log, one {@code SINK_FAILURE} on recovery), with no
 * time budget (ruled 2026-10-04: a restart would lose what is held and fix nothing about the
 * database — the queues bound the hold, and shedding is announced the moment it starts), and
 * {@link #stop()} is honoured mid-hold. Any other failure is terminal: kept in {@link #failure()},
 * {@code onDeath} fires at once, never re-drained into a broken sink (P9 fail-closed). Each sealed
 * bar then feeds gap detection, and {@code DLG_FLAG} transitions become {@code market_state_change}
 * events: both are derived observability written to the event log, and — unlike the sink — are
 * <b>best-effort</b>: a failing write is counted in {@link #observabilityFailures()} and surfaced
 * in the heartbeat (loud), never thrown (ruled 2026-10-03). Ticks are best-effort by design
 * (shed-oldest queue).
 */
public final class Pump implements Runnable {

    private static final Duration IDLE_WAIT = Duration.ofMillis(250);
    /** A hold sleeps in slices this long, so {@link #stop()} is honoured within one of them. */
    private static final Duration HOLD_SLICE = Duration.ofMillis(250);
    private static final int TICK_BATCH = 5_000;

    private final Buffers queues;
    private final CaptureStore sink;
    private final GapDetector gapDetector;
    private final GapStore gaps;
    private final EventLog events;
    private final Sleeper sleeper;
    private final Clock clock;
    private final BackoffPolicy backoff;
    private final Consumer<String> log;
    private final Consumer<RuntimeException> onDeath;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong observabilityFailures = new AtomicLong();
    private final AtomicLong sinkFailures = new AtomicLong();
    private volatile boolean running = true;
    private volatile @Nullable RuntimeException failure;

    public Pump(Buffers queues, CaptureStore sink, GapDetector gapDetector, GapStore gaps,
            EventLog events, Sleeper sleeper, Clock clock, BackoffPolicy backoff,
            Consumer<String> log, Consumer<RuntimeException> onDeath) {
        this.queues = queues;
        this.sink = sink;
        this.gapDetector = gapDetector;
        this.gaps = gaps;
        this.events = events;
        this.sleeper = sleeper;
        this.clock = clock;
        this.backoff = backoff;
        this.log = log;
        this.onDeath = onDeath;
    }

    int drainOnce() {
        int count = 0;
        Bar1m bar;
        while ((bar = queues.peekBarNow()) != null) {
            sink.write(bar);
            queues.removeBarNow();
            written.incrementAndGet();
            detectGap(bar);
            count++;
        }
        Buffers.StateChange state;
        while ((state = queues.peekStateChangeNow()) != null) {
            recordStateChange(state);
            queues.removeStateChangeNow();
            count++;
        }
        Tick tick;
        for (int i = 0; i < TICK_BATCH && (tick = queues.pollTickNow()) != null; i++) {
            sink.write(tick);
            written.incrementAndGet();
            count++;
        }
        return count;
    }

    // Best-effort by ruling: the bar is already persisted and acked before we get here, so a
    // failing gap/event write is counted (loud) and swallowed — never allowed to halt capture.
    private void detectGap(Bar1m bar) {
        try {
            GapDetector.Gap gap = gapDetector.onSealedBar(bar.epic(), bar.startUtc());
            if (gap != null) {
                gaps.record(gap);
                events.write(ServiceEvent.of(EventType.BAR_GAP, bar.startUtc())
                        .forEpic(bar.epic())
                        .withDetail(mapper.createObjectNode()
                                .put("gapFromUtc", gap.gapFromUtc().toString())
                                .put("gapToUtc", gap.gapToUtc().toString())
                                .put("missingMinutes", gap.missingMinutes())));
            }
        } catch (RuntimeException e) {
            observabilityFailures.incrementAndGet();
        }
    }

    private void recordStateChange(Buffers.StateChange state) {
        try {
            events.write(ServiceEvent.of(EventType.MARKET_STATE_CHANGE, state.atUtc())
                    .forEpic(state.epic())
                    .withDetail(mapper.createObjectNode().put("dealFlag", state.dealFlag())));
        } catch (RuntimeException e) {
            observabilityFailures.incrementAndGet();
        }
    }

    /** One pass — drain, or flush and idle; a retryable sink failure holds here. The unit
     * {@link #run()} repeats and the scenario harness drives directly. */
    public void cycle() throws InterruptedException {
        try {
            if (drainOnce() == 0) {
                sink.flush();
                sleeper.sleep(IDLE_WAIT);
            }
        } catch (PersistenceException e) {
            if (!e.retryable()) {
                throw e; // configuration, schema or data — never retry what we cannot name
            }
            hold(e);
        }
    }

    /** Hold-and-retry (E1-T9): the data is already safe — bars queued, the store's batch held —
     * so wait, paced by backoff from its floor, until the sink recovers. One episode is one
     * {@link #sinkFailures()} count and one {@code SINK_FAILURE} event, dated from its first
     * failure and written once the database is back (nothing is attempted while it is down). */
    private void hold(PersistenceException cause) throws InterruptedException {
        sinkFailures.incrementAndGet();
        long since = clock.monotonicNanos();
        Instant failedAt = clock.wallInstant();
        long droppedBefore = queues.droppedTicks();
        boolean lossy = false;
        log.accept("sink unavailable — holding " + queues.pendingWrites() + " queued writes: " + cause);
        for (int attempt = 1; running; attempt++) {
            Duration wait = backoff.delayFor(attempt);
            log.accept("sink retry " + attempt + " in " + wait.toSeconds() + "s");
            if (!holdFor(wait)) {
                return; // stopped mid-hold — the shutdown path takes over
            }
            if (!lossy && queues.droppedTicks() > droppedBefore) {
                lossy = true;
                log.accept("the hold is now lossy — the tick queue has begun shedding");
            }
            try {
                sink.recover();
            } catch (PersistenceException e) {
                if (!e.retryable()) {
                    throw e;
                }
                log.accept("sink still unavailable after attempt " + attempt + ": " + e);
                continue;
            }
            Duration outage = Duration.ofNanos(clock.monotonicNanos() - since);
            long shed = queues.droppedTicks() - droppedBefore;
            log.accept("sink recovered after " + outage.toSeconds() + "s and " + attempt
                    + " attempt(s); " + queues.pendingWrites() + " queued writes to drain"
                    + (shed > 0 ? "; " + shed + " ticks shed during the hold" : ""));
            recordRecovery(cause, failedAt, outage, attempt, shed);
            return;
        }
    }

    /** Sleep in slices so {@link #stop()} is honoured promptly; false if stopped before the wait
     * elapsed. */
    private boolean holdFor(Duration wait) throws InterruptedException {
        long deadline = clock.monotonicNanos() + wait.toNanos();
        while (running) {
            long remaining = deadline - clock.monotonicNanos();
            if (remaining <= 0) {
                return true;
            }
            sleeper.sleep(Duration.ofNanos(Math.min(remaining, HOLD_SLICE.toNanos())));
        }
        return false;
    }

    private void recordRecovery(PersistenceException cause, Instant failedAt, Duration outage,
            int attempts, long shed) {
        try {
            events.write(ServiceEvent.of(EventType.SINK_FAILURE, failedAt)
                    .withDetail(mapper.createObjectNode()
                            .put("cause", cause.getMessage() + " — " + cause.getCause())
                            .put("outageMs", outage.toMillis())
                            .put("attempts", attempts)
                            .put("ticksShed", shed)
                            .put("queuedAtRecovery", queues.pendingWrites())));
        } catch (RuntimeException e) {
            observabilityFailures.incrementAndGet(); // the breadcrumb is Tier 2 — counted, never thrown
        }
    }

    @Override
    public void run() {
        try {
            while (running) {
                cycle();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (RuntimeException e) {
            die(e);
            return;
        }
        drainTail();
    }

    /** A death in service: the cause is kept, recorded best-effort so the console learns why
     * capture died (D28), and announced at once — unless {@link #stop()} already ran, in which case
     * the shutdown path owns it. {@link #run()} does this on the pump thread; the scenario harness
     * calls it in run()'s place when {@link #cycle()} throws. */
    public void die(RuntimeException cause) {
        failure = cause;
        if (running) {
            recordDeath(cause);
            onDeath.accept(cause);
        }
    }

    /** The tail after {@link #stop()}: one last drain and flush into whatever sink there is. A
     * failure here is recorded, not held — the process is already leaving. {@link #run()} does this
     * on the pump thread; the scenario harness calls it in run()'s place. */
    public void drainTail() {
        try {
            drainOnce();
            sink.flush();
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    private void recordDeath(RuntimeException cause) {
        try {
            events.write(ServiceEvent.of(EventType.DB_ERROR, clock.wallInstant())
                    .withDetail(mapper.createObjectNode()
                            .put("cause", String.valueOf(cause))
                            .put("queued", queues.pendingWrites())));
        } catch (RuntimeException e) {
            observabilityFailures.incrementAndGet();
        }
    }

    public void stop() {
        running = false;
    }

    /** Writes the sink accepted — a batched tick counts when the store takes it, not when it
     * lands — the heartbeat's honest number, within one per tick-side blip. */
    public long writtenCount() {
        return written.get();
    }

    /** Derived-observability writes that failed and were swallowed — nonzero is the alarm. */
    public long observabilityFailures() {
        return observabilityFailures.get();
    }

    /** Sink outages held through (one per episode, however many retries) — the database blinked. */
    public long sinkFailures() {
        return sinkFailures.get();
    }

    public @Nullable RuntimeException failure() {
        return failure;
    }
}
