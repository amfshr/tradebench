package dev.amfshr.tradebench.marketdata.ingest;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.amfshr.tradebench.core.domain.Bar1m;
import dev.amfshr.tradebench.core.domain.Tick;
import dev.amfshr.tradebench.ig.time.Sleeper;
import dev.amfshr.tradebench.marketdata.coverage.GapDetector;
import dev.amfshr.tradebench.marketdata.events.EventType;
import dev.amfshr.tradebench.marketdata.events.ServiceEvent;
import dev.amfshr.tradebench.marketdata.store.CaptureStore;
import dev.amfshr.tradebench.marketdata.store.EventLog;
import dev.amfshr.tradebench.marketdata.store.GapStore;

/**
 * Single consumer of the capture queues. Bars drain first (the must-persist backbone) straight
 * to the sink; a sink failure stops the pump and is kept in {@link #failure()} — never
 * re-drained into a broken sink, never silent (P9 fail-closed). Each sealed bar then feeds gap
 * detection, and {@code DLG_FLAG} transitions become {@code market_state_change} events: both are
 * derived observability written to the event log, and — unlike the sink — are <b>best-effort</b>.
 * A failing gap/event write is counted in {@link #observabilityFailures()} and surfaced in the
 * heartbeat (loud), never thrown: the product plane must not halt to protect a breadcrumb
 * (ruled 2026-10-03). Ticks are best-effort by design (shed-oldest queue).
 */
public final class Pump implements Runnable {

    private static final Duration IDLE_WAIT = Duration.ofMillis(250);
    private static final int TICK_BATCH = 5_000;

    private final Buffers queues;
    private final CaptureStore sink;
    private final GapDetector gapDetector;
    private final GapStore gaps;
    private final EventLog events;
    private final Sleeper sleeper;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong observabilityFailures = new AtomicLong();
    private volatile boolean running = true;
    private volatile @Nullable RuntimeException failure;

    public Pump(Buffers queues, CaptureStore sink, GapDetector gapDetector, GapStore gaps,
            EventLog events, Sleeper sleeper) {
        this.queues = queues;
        this.sink = sink;
        this.gapDetector = gapDetector;
        this.gaps = gaps;
        this.events = events;
        this.sleeper = sleeper;
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

    void cycle() throws InterruptedException {
        if (drainOnce() == 0) {
            sink.flush();
            sleeper.sleep(IDLE_WAIT);
        }
    }

    @Override
    public void run() {
        try {
            while (running) {
                cycle();
            }
            drainOnce();
            sink.flush();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    public void stop() {
        running = false;
    }

    /** Events actually written to the sink — the heartbeat's honest number. */
    public long writtenCount() {
        return written.get();
    }

    /** Derived-observability writes that failed and were swallowed — nonzero is the alarm. */
    public long observabilityFailures() {
        return observabilityFailures.get();
    }

    public @Nullable RuntimeException failure() {
        return failure;
    }
}
