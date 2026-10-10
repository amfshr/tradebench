package dev.amfshr.tradebench.marketdata.supervise;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import dev.amfshr.tradebench.core.time.Clock;
import dev.amfshr.tradebench.marketdata.events.CaptureStatus;
import dev.amfshr.tradebench.marketdata.store.StatusStore;

/**
 * Publishes one {@link CaptureStatus} row per (instance, market) each heartbeat, on the heartbeat
 * thread, from volatile/concurrent views only — every market's row in one round trip, so a
 * database that is down costs one connection wait, not one per market (E1-T10 #22). Observability
 * is best-effort: a failing publish is counted in {@link #statusFailures()}, never thrown.
 */
public final class HealthProbe {

    private final String instance;
    private final List<String> epics;
    private final MarketTelemetry markets;
    private final BeltView belt;
    private final Clock clock;
    private final StatusStore store;
    private final AtomicLong failures = new AtomicLong();

    public HealthProbe(String instance, List<String> epics, MarketTelemetry markets, BeltView belt,
            Clock clock, StatusStore store) {
        this.instance = instance;
        this.epics = List.copyOf(epics);
        this.markets = markets;
        this.belt = belt;
        this.clock = clock;
        this.store = store;
    }

    /** This market's row as of now — pure assembly, so the column mapping is testable without a store. */
    public CaptureStatus snapshot(String epic) {
        MarketTelemetry.MarketCounts counts = markets.countsFor(epic);
        return new CaptureStatus(instance, epic, clock.wallInstant(), belt.stateOf(epic),
                markets.dealFlag(epic), counts.lastTickAt(), counts.lastBarAt(), counts.ticks(),
                counts.bars(), counts.dropped(), counts.malformed(), belt.reconnectsTotal(),
                markets.pendingWrites());
    }

    /** UPSERT every market's row, in one batch; a failing publish is counted once, never thrown. */
    public void publish() {
        List<CaptureStatus> rows = epics.stream().map(this::snapshot).toList();
        try {
            store.upsert(rows);
        } catch (RuntimeException e) {
            failures.incrementAndGet();
        }
    }

    /** Publishes that failed and were swallowed — nonzero is the alarm. */
    public long statusFailures() {
        return failures.get();
    }
}
