package dev.amfshr.tradebench.marketdata.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.core.domain.Bar1m;
import dev.amfshr.tradebench.core.domain.Tick;
import dev.amfshr.tradebench.ig.stream.Ohlc;
import dev.amfshr.tradebench.ig.stream.SealedBarUpdate;
import dev.amfshr.tradebench.ig.stream.TickUpdate;
import dev.amfshr.tradebench.ig.time.Sleeper;
import dev.amfshr.tradebench.marketdata.coverage.GapDetector;
import dev.amfshr.tradebench.marketdata.events.EventCategory;
import dev.amfshr.tradebench.marketdata.events.EventType;
import dev.amfshr.tradebench.marketdata.events.ServiceEvent;
import dev.amfshr.tradebench.marketdata.events.Severity;
import dev.amfshr.tradebench.marketdata.store.CaptureStore;
import dev.amfshr.tradebench.marketdata.store.EventLog;
import dev.amfshr.tradebench.marketdata.store.GapStore;

class PumpTest {

    private static final String DAX = "IX.D.DAX.DAILY.IP";
    private static final Sleeper NO_SLEEP = duration -> {
    };

    private static class RecordingSink implements CaptureStore {
        final List<String> order = new ArrayList<>();
        int flushes;
        int recoveries;

        @Override
        public void write(Bar1m bar) {
            order.add("bar@" + bar.startUtc().getEpochSecond());
        }

        @Override
        public void write(Tick tick) {
            order.add("tick@" + tick.timestamp().getEpochSecond());
        }

        @Override
        public void flush() {
            flushes++;
        }

        @Override
        public void recover() {
            recoveries++;
        }

        @Override
        public void close() {
        }
    }

    private static final class FakeEventLog implements EventLog {
        final List<ServiceEvent> events = new ArrayList<>();

        @Override
        public void write(ServiceEvent event) {
            events.add(event);
        }
    }

    private static final class FakeGapStore implements GapStore {
        final List<GapDetector.Gap> gaps = new ArrayList<>();

        @Override
        public void record(GapDetector.Gap gap) {
            gaps.add(gap);
        }
    }

    private static TickUpdate tick(long second) {
        return new TickUpdate(DAX, Instant.ofEpochSecond(second), BigDecimal.ONE,
                BigDecimal.TWO, "DEAL");
    }

    private static SealedBarUpdate bar(long second) {
        return new SealedBarUpdate(DAX, Instant.ofEpochSecond(second),
                new Ohlc(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE),
                new Ohlc(BigDecimal.TWO, BigDecimal.TWO, BigDecimal.TWO, BigDecimal.TWO),
                null);
    }

    @Test
    void drainsBarsFirstThenTicksAndRoutesStateChangesToTheEventLog() {
        Buffers queues = new Buffers(10, () -> 0L);
        RecordingSink sink = new RecordingSink();
        FakeGapStore gaps = new FakeGapStore();
        FakeEventLog events = new FakeEventLog();
        queues.onTick(tick(10));   // null -> DEAL is a transition: one state change
        queues.onTick(tick(11));   // still DEAL: no new state change
        queues.onSealedBar(bar(60));
        Pump pump = new Pump(queues, sink, new GapDetector(), gaps, events, NO_SLEEP);

        int worked = pump.drainOnce();

        assertEquals(List.of("bar@60", "tick@10", "tick@11"), sink.order,
                "the sink carries market data only (decision #1) — no state row");
        assertEquals(1, events.events.size(), "the DLG_FLAG transition becomes one event");
        ServiceEvent stateEvent = events.events.get(0);
        assertEquals(EventType.MARKET_STATE_CHANGE, stateEvent.type());
        // Pin the catalogue pairing (D25) — the only place it is now anchored.
        assertEquals(EventCategory.DATA_LIVENESS, stateEvent.category());
        assertEquals(Severity.INFO, stateEvent.severity());
        assertEquals("DEAL", stateEvent.detail().get("dealFlag").asText());
        assertEquals(4, worked, "bar + state + two ticks were all drained");
        assertEquals(3, pump.writtenCount(),
                "writtenCount is sink writes — observability events are not sink writes");
    }

    @Test
    void aSealedBarRevealingAGapRecordsItAndEmitsABarGapEvent() {
        Buffers queues = new Buffers(10, () -> 0L);
        RecordingSink sink = new RecordingSink();
        FakeGapStore gaps = new FakeGapStore();
        FakeEventLog events = new FakeEventLog();
        queues.onSealedBar(bar(0));     // minute 0 seeds the watermark
        queues.onSealedBar(bar(180));   // minute 3 -> minutes 1 and 2 are missing
        Pump pump = new Pump(queues, sink, new GapDetector(), gaps, events, NO_SLEEP);

        pump.drainOnce();

        assertEquals(1, gaps.gaps.size(), "the hole is recorded once");
        GapDetector.Gap gap = gaps.gaps.get(0);
        assertEquals(DAX, gap.epic());
        assertEquals(2, gap.missingMinutes());
        assertEquals(Instant.ofEpochSecond(60), gap.gapFromUtc());
        assertEquals(Instant.ofEpochSecond(120), gap.gapToUtc());
        assertEquals(1, events.events.size(), "and is announced as one bar_gap event");
        ServiceEvent event = events.events.get(0);
        assertEquals(EventType.BAR_GAP, event.type());
        assertEquals(DAX, event.epic());
        assertEquals(2, event.detail().get("missingMinutes").asInt());
    }

    @Test
    void aFailingObservabilityWriteNeitherStopsThePumpNorStallsTheProductPlane() {
        Buffers queues = new Buffers(10, () -> 0L);
        RecordingSink sink = new RecordingSink();
        queues.onSealedBar(bar(0));
        queues.onSealedBar(bar(180));   // reveals a gap -> gaps.record is attempted and fails
        GapStore boom = gap -> {
            throw new RuntimeException("bar_gaps insert failed");
        };
        Pump pump = new Pump(queues, sink, new GapDetector(), boom, new FakeEventLog(), NO_SLEEP);
        pump.stop();

        pump.run();

        assertNull(pump.failure(),
                "a derived-observability write must never halt capture (ruled 2026-10-03)");
        assertEquals(1, pump.observabilityFailures(),
                "but the failure is counted — loud, not silent");
        assertEquals(List.of("bar@0", "bar@180"), sink.order,
                "both bars persisted — the product plane never stalled on a breadcrumb");
    }

    @Test
    void aFailingEventWriteOnAStateChangeDoesNotHaltCapture() {
        Buffers queues = new Buffers(10, () -> 0L);
        RecordingSink sink = new RecordingSink();
        queues.onTick(tick(10));     // null -> DEAL: a state change whose event write will fail
        queues.onSealedBar(bar(60)); // a seed bar -> no gap, so only the state path writes an event
        EventLog boom = event -> {
            throw new RuntimeException("service_events insert failed");
        };
        Pump pump = new Pump(queues, sink, new GapDetector(), new FakeGapStore(), boom, NO_SLEEP);
        pump.stop();

        pump.run();

        assertNull(pump.failure(),
                "a failing market_state_change write must not halt capture (ruled 2026-10-03)");
        assertEquals(1, pump.observabilityFailures(),
                "the state-event failure is counted — loud, not silent");
        assertEquals(List.of("bar@60", "tick@10"), sink.order,
                "the bar and tick still persisted — the product plane never stalled");
    }

    @Test
    void idleCycleFlushesTheSink() throws InterruptedException {
        RecordingSink sink = new RecordingSink();
        Pump pump = new Pump(new Buffers(10, () -> 0L), sink, new GapDetector(), new FakeGapStore(),
                new FakeEventLog(), NO_SLEEP);

        pump.cycle();

        assertEquals(1, sink.flushes, "idle moments flush buffered writes to disk");
    }

    @Test
    void stoppedRunStillDrainsTheTail() {
        Buffers queues = new Buffers(10, () -> 0L);
        RecordingSink sink = new RecordingSink();
        queues.onSealedBar(bar(60));
        Pump pump = new Pump(queues, sink, new GapDetector(), new FakeGapStore(),
                new FakeEventLog(), NO_SLEEP);
        pump.stop();

        pump.run();

        assertEquals(List.of("bar@60"), sink.order,
                "the day's last minute must survive shutdown");
        assertEquals(1, sink.flushes);
    }

    @Test
    void sinkFailureStopsThePumpKeepsTheBarAndKeepsTheCause() {
        Buffers queues = new Buffers(10, () -> 0L);
        queues.onSealedBar(bar(60));
        UncheckedIOException boom = new UncheckedIOException("disk full",
                new java.io.IOException("disk full"));
        var failingSink = new RecordingSink() {
            int barAttempts;

            @Override
            public void write(Bar1m bar) {
                barAttempts++;
                throw boom;
            }
        };
        Pump pump = new Pump(queues, failingSink, new GapDetector(), new FakeGapStore(),
                new FakeEventLog(), NO_SLEEP);

        pump.run();

        assertSame(boom, pump.failure(), "the cause survives for the runner to report");
        assertEquals(1, failingSink.barAttempts,
                "no re-drain into a broken sink — it would mask the original failure");
        assertNotNull(queues.peekBarNow(),
                "ack-after-apply: the unwritten bar stays queued, not silently lost");
    }
}
