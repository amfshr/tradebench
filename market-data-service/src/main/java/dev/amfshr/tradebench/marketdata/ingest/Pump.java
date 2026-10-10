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
import dev.amfshr.tradebench.marketdata.store.BestEffortEventLog;
import dev.amfshr.tradebench.marketdata.store.CaptureStore;
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
 * events: both are derived observability, and — unlike the sink — <b>best-effort</b> (ruled
 * 2026-10-03): the event log is a {@link BestEffortEventLog}, whose failures are its own, and a
 * failing gap write is counted in {@link #gapWriteFailures()} — surfaced in the heartbeat (loud),
 * never thrown. Ticks are best-effort by design (shed-oldest queue).
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
    private final BestEffortEventLog events;
    private final Sleeper sleeper;
    private final Clock clock;
    private final BackoffPolicy backoff;
    private final Consumer<String> log;
    private final Consumer<RuntimeException> onDeath;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong gapWriteFailures = new AtomicLong();
    private final AtomicLong sinkFailures = new AtomicLong();
    private volatile boolean running = true;
    private volatile @Nullable RuntimeException failure;
    private @Nullable Episode episode;

    /** The sink outage in progress: opened by a retryable failure, closed only once a write lands
     * after a recovery. A recovery that only reconnected is provisional — if the next write fails
     * before one lands, the same episode continues and the ladder keeps climbing (E1-T10 #6). */
    private static final class Episode {
        final PersistenceException cause;
        final long since;
        final Instant failedAt;
        final long droppedBefore;
        int attempts;
        boolean lossy;
        boolean recovered;
        long shed;
        int queuedAtRecovery;
        int ticksWrittenSinceRecovery;

        Episode(PersistenceException cause, long since, Instant failedAt, long droppedBefore) {
            this.cause = cause;
            this.since = since;
            this.failedAt = failedAt;
            this.droppedBefore = droppedBefore;
        }
    }

    public Pump(Buffers queues, CaptureStore sink, GapDetector gapDetector, GapStore gaps,
            BestEffortEventLog events, Sleeper sleeper, Clock clock, BackoffPolicy backoff,
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
            landed(); // a bar is its own round trip
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
            if (episode != null) {
                episode.ticksWrittenSinceRecovery++; // batched: the flush is the round trip
            }
            written.incrementAndGet();
            count++;
        }
        return count;
    }

    // Best-effort by ruling: the bar is already persisted and acked before we get here, so a
    // failing gap write is counted (loud) and swallowed — never allowed to halt capture.
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
            gapWriteFailures.incrementAndGet();
        }
    }

    private void recordStateChange(Buffers.StateChange state) {
        events.write(ServiceEvent.of(EventType.MARKET_STATE_CHANGE, state.atUtc())
                .forEpic(state.epic())
                .withDetail(mapper.createObjectNode().put("dealFlag", state.dealFlag())));
    }

    /** One pass — drain, or flush and idle; a retryable sink failure holds here. The unit
     * {@link #run()} repeats and the scenario harness drives directly. */
    public void cycle() throws InterruptedException {
        try {
            if (drainOnce() == 0) {
                flush();
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
     * failure and written once a write has landed again (nothing is attempted while it is down). A
     * failure after a recovery that only reconnected continues the episode (E1-T10 #6). */
    private void hold(PersistenceException cause) throws InterruptedException {
        Episode current = episode;
        if (current == null) {
            current = new Episode(cause, clock.monotonicNanos(), clock.wallInstant(), queues.droppedTicks());
            episode = current;
            sinkFailures.incrementAndGet();
            log.accept("sink unavailable — holding " + queues.pendingWrites() + " queued writes: " + cause);
        } else {
            current.recovered = false;
            log.accept("sink failed again before a write landed — the episode continues at attempt "
                    + (current.attempts + 1) + ": " + cause);
        }
        while (running) {
            current.attempts++;
            Duration wait = backoff.delayFor(current.attempts);
            log.accept("sink retry " + current.attempts + " in " + wait.toSeconds() + "s");
            if (!holdFor(wait)) {
                return; // stopped mid-hold — the shutdown path takes over
            }
            if (!current.lossy && queues.droppedTicks() > current.droppedBefore) {
                current.lossy = true;
                log.accept("the hold is now lossy — the tick queue has begun shedding");
            }
            boolean landedHeld;
            try {
                landedHeld = sink.recover();
            } catch (PersistenceException e) {
                if (!e.retryable()) {
                    throw e;
                }
                log.accept("sink still unavailable after attempt " + current.attempts + ": " + e);
                continue;
            }
            recovered(current, landedHeld);
            return;
        }
    }

    /** A recovery returned: the episode is over if the recovery itself landed held writes, else
     * provisional until one lands. */
    private void recovered(Episode current, boolean landedHeld) {
        current.recovered = true;
        current.ticksWrittenSinceRecovery = 0;
        current.shed = queues.droppedTicks() - current.droppedBefore;
        current.queuedAtRecovery = queues.pendingWrites();
        log.accept("sink recovered after " + Duration.ofNanos(clock.monotonicNanos() - current.since).toSeconds()
                + "s and " + current.attempts + " attempt(s); " + current.queuedAtRecovery
                + " queued writes to drain"
                + (current.shed > 0 ? "; " + current.shed + " ticks shed during the hold" : "")
                + (landedHeld ? "; the held batch landed" : ""));
        if (landedHeld) {
            landed();
        }
    }

    /** A write landed after a recovery: the episode is over, and only now is it recorded — a
     * recovery that merely reconnected proves nothing (E1-T10 #6). */
    private void landed() {
        Episode current = episode;
        if (current != null && current.recovered) {
            episode = null;
            recordRecovery(current, Duration.ofNanos(clock.monotonicNanos() - current.since));
        }
    }

    /** The idle flush: a round trip only if ticks were batched since the recovery — an empty flush
     * is a no-op in the store and proves nothing. */
    private void flush() {
        sink.flush();
        Episode current = episode;
        if (current != null && current.ticksWrittenSinceRecovery > 0) {
            landed();
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

    private void recordRecovery(Episode over, Duration outage) {
        events.write(ServiceEvent.of(EventType.SINK_FAILURE, over.failedAt)
                .withDetail(mapper.createObjectNode()
                        .put("cause", over.cause.getMessage() + " — " + over.cause.getCause())
                        .put("outageMs", outage.toMillis())
                        .put("attempts", over.attempts)
                        .put("ticksShed", over.shed)
                        .put("queuedAtRecovery", over.queuedAtRecovery)));
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

    /** The tail after {@link #stop()}: drain everything and flush into whatever sink there is. A
     * sink broken at stop gets one recovery attempt with no wait — a restart must not lose what
     * the database would take (E1-T10 #20) — and a failure past that is recorded, not held: the
     * process is already leaving. {@link #run()} does this on the pump thread; the scenario
     * harness calls it in run()'s place. */
    public void drainTail() {
        try {
            drainAll();
        } catch (PersistenceException e) {
            if (!e.retryable()) {
                failure = e;
                return;
            }
            try {
                Episode current = episode;
                if (current == null) { // broken by this very drain: an episode of one attempt
                    current = new Episode(e, clock.monotonicNanos(), clock.wallInstant(), queues.droppedTicks());
                    episode = current;
                    sinkFailures.incrementAndGet();
                }
                current.attempts++;
                recovered(current, sink.recover());
                drainAll();
            } catch (RuntimeException again) {
                failure = again;
            }
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    private void drainAll() {
        while (drainOnce() > 0) {
            // every batch, not one — a stop with a backlog lands all of it
        }
        flush();
    }

    private void recordDeath(RuntimeException cause) {
        events.write(ServiceEvent.of(EventType.DB_ERROR, clock.wallInstant())
                .withDetail(mapper.createObjectNode()
                        .put("cause", String.valueOf(cause))
                        .put("queued", queues.pendingWrites())));
    }

    public void stop() {
        running = false;
    }

    /** Writes the sink accepted — a batched tick counts when the store takes it, not when it
     * lands — the heartbeat's honest number, within one per tick-side blip. */
    public long writtenCount() {
        return written.get();
    }

    /** Gap writes that failed and were swallowed — nonzero is the alarm; the event log counts its own. */
    public long gapWriteFailures() {
        return gapWriteFailures.get();
    }

    /** Sink outages held through — one per episode, however many retries or provisional
     * recoveries — the database blinked. */
    public long sinkFailures() {
        return sinkFailures.get();
    }

    public @Nullable RuntimeException failure() {
        return failure;
    }
}
