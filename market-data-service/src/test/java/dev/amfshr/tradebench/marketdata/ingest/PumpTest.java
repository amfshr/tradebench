package dev.amfshr.tradebench.marketdata.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.UncheckedIOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.core.domain.Bar1m;
import dev.amfshr.tradebench.core.domain.Tick;
import dev.amfshr.tradebench.core.time.Clock;
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
import dev.amfshr.tradebench.marketdata.store.PersistenceException;
import dev.amfshr.tradebench.marketdata.supervise.BackoffPolicy;
import dev.amfshr.tradebench.marketdata.supervise.Tuning;

class PumpTest {

    private static final String DAX = "IX.D.DAX.DAILY.IP";
    private static final Sleeper NO_SLEEP = duration -> {
    };
    /** jitter = 1.0: the playbook ladder exactly — 1s base doubling under a 5s floor, 60s cap. */
    private static final BackoffPolicy BACKOFF = new BackoffPolicy(Tuning.playbook(), () -> 1.0);
    private static final Consumer<String> LOG_NOWHERE = message -> {
    };
    private static final Consumer<RuntimeException> NO_DEATH = cause -> {
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
        Pump pump = new Pump(queues, sink, new GapDetector(), gaps, events, NO_SLEEP, new FakeClock(), BACKOFF, LOG_NOWHERE, NO_DEATH);

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
        Pump pump = new Pump(queues, sink, new GapDetector(), gaps, events, NO_SLEEP, new FakeClock(), BACKOFF, LOG_NOWHERE, NO_DEATH);

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
        Pump pump = new Pump(queues, sink, new GapDetector(), boom, new FakeEventLog(), NO_SLEEP, new FakeClock(), BACKOFF, LOG_NOWHERE, NO_DEATH);
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
        Pump pump = new Pump(queues, sink, new GapDetector(), new FakeGapStore(), boom, NO_SLEEP, new FakeClock(), BACKOFF, LOG_NOWHERE, NO_DEATH);
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
                new FakeEventLog(), NO_SLEEP, new FakeClock(), BACKOFF, LOG_NOWHERE, NO_DEATH);

        pump.cycle();

        assertEquals(1, sink.flushes, "idle moments flush buffered writes to disk");
    }

    @Test
    void stoppedRunStillDrainsTheTail() {
        Buffers queues = new Buffers(10, () -> 0L);
        RecordingSink sink = new RecordingSink();
        queues.onSealedBar(bar(60));
        Pump pump = new Pump(queues, sink, new GapDetector(), new FakeGapStore(),
                new FakeEventLog(), NO_SLEEP, new FakeClock(), BACKOFF, LOG_NOWHERE, NO_DEATH);
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
        List<RuntimeException> deaths = new ArrayList<>();
        Pump pump = new Pump(queues, failingSink, new GapDetector(), new FakeGapStore(),
                new FakeEventLog(), NO_SLEEP, new FakeClock(), BACKOFF, LOG_NOWHERE, deaths::add);

        pump.run();

        assertSame(boom, pump.failure(), "the cause survives for the runner to report");
        assertEquals(List.of(boom), deaths, "onDeath fires at once — not a heartbeat later");
        assertEquals(1, failingSink.barAttempts,
                "no re-drain into a broken sink — it would mask the original failure");
        assertNotNull(queues.peekBarNow(),
                "ack-after-apply: the unwritten bar stays queued, not silently lost");
    }

    // --- E1-T9: hold-and-retry -----------------------------------------------------------------

    private static PersistenceException retryable(String what) {
        return new PersistenceException(what, new SQLException(what, "08006"));
    }

    private static PersistenceException terminal(String what) {
        return new PersistenceException(what, new SQLException(what, "23505"));
    }

    private static final class FakeClock implements Clock {
        long nanos;
        Instant wall = Instant.parse("2026-09-28T09:00:00Z");

        @Override
        public Instant wallInstant() {
            return wall;
        }

        @Override
        public long monotonicNanos() {
            return nanos;
        }

        void advance(Duration by) {
            nanos += by.toNanos();
            wall = wall.plus(by);
        }

        long seconds() {
            return nanos / 1_000_000_000L;
        }
    }

    /** Sleeping advances the fake clock by exactly the request, so a hold's waits are measurable;
     * a runaway hold fails the test instead of hanging it. */
    private static final class FakeSleeper implements Sleeper {
        private final FakeClock clock;
        int sleeps;
        Runnable onSleep = () -> {
        };

        FakeSleeper(FakeClock clock) {
            this.clock = clock;
        }

        @Override
        public void sleep(Duration duration) {
            if (++sleeps > 100_000) {
                throw new IllegalStateException("runaway hold — stop() not honoured?");
            }
            clock.advance(duration);
            onSleep.run();
        }
    }

    /** A sink whose next writes/flushes fail as armed, in order, and whose recover() may refuse. */
    private static final class FlakySink extends RecordingSink {
        final Deque<RuntimeException> armed = new ArrayDeque<>();
        final List<Long> recoverCalledAtSeconds = new ArrayList<>();
        int recoverRefusals;
        @Nullable RuntimeException recoverFailure; // thrown by the next recover(), once
        private final FakeClock clock;

        FlakySink(FakeClock clock) {
            this.clock = clock;
        }

        private void failIfArmed() {
            RuntimeException next = armed.poll();
            if (next != null) {
                throw next;
            }
        }

        @Override
        public void write(Bar1m bar) {
            failIfArmed();
            super.write(bar);
        }

        @Override
        public void write(Tick tick) {
            failIfArmed();
            super.write(tick);
        }

        @Override
        public void flush() {
            failIfArmed();
            super.flush();
        }

        @Override
        public void recover() {
            recoverCalledAtSeconds.add(clock.seconds());
            super.recover();
            if (recoverFailure != null) {
                RuntimeException once = recoverFailure;
                recoverFailure = null;
                throw once;
            }
            if (recoverRefusals-- > 0) {
                throw retryable("still down");
            }
        }
    }

    private record Rig(Buffers queues, FlakySink sink, FakeEventLog events, FakeClock clock,
            FakeSleeper sleeper, List<String> log, List<RuntimeException> deaths, Pump pump) {
    }

    private static Rig rig(int tickCapacity) {
        FakeClock clock = new FakeClock();
        FakeSleeper sleeper = new FakeSleeper(clock);
        Buffers queues = new Buffers(tickCapacity, () -> clock.nanos);
        FlakySink sink = new FlakySink(clock);
        FakeEventLog events = new FakeEventLog();
        List<String> log = new ArrayList<>();
        List<RuntimeException> deaths = new ArrayList<>();
        Pump pump = new Pump(queues, sink, new GapDetector(), new FakeGapStore(), events, sleeper,
                clock, BACKOFF, log::add, deaths::add);
        return new Rig(queues, sink, events, clock, sleeper, log, deaths, pump);
    }

    @Test
    void aRetryableSinkFailureHoldsThenRecoversAndTheBarLandsExactlyOnce() throws InterruptedException {
        Rig r = rig(10);
        r.queues().onSealedBar(bar(60));
        r.sink().armed.add(retryable("bar write failed"));

        r.pump().cycle(); // the write fails → hold → one wait → recover
        r.pump().cycle(); // the bar is re-drained into the recovered sink

        assertEquals(List.of("bar@60"), r.sink().order, "held, then written once — never lost, never doubled");
        assertEquals(List.of(5L), r.sink().recoverCalledAtSeconds, "the first retry waits the 5s floor");
        assertEquals(1, r.pump().sinkFailures());
        assertTrue(r.deaths().isEmpty(), "a blip is not a death");
        ServiceEvent event = r.events().events.get(0);
        assertEquals(EventType.SINK_FAILURE, event.type());
        assertEquals(Instant.parse("2026-09-28T09:00:00Z"), event.eventTimeUtc(),
                "the episode is dated from its first failure, not its recovery");
        assertEquals(5_000, event.detail().get("outageMs").asLong());
        assertEquals(1, event.detail().get("attempts").asInt());
        assertEquals(0, event.detail().get("ticksShed").asLong());
        assertEquals(1, event.detail().get("queuedAtRecovery").asInt(), "the bar, still queued");
        assertTrue(event.detail().get("cause").asText().contains("bar write failed"));
        assertTrue(r.log().get(0).contains("holding 1 queued write"), r.log().get(0));
    }

    @Test
    void recoveryAttemptsAreBackoffPacedFromTheFloor() throws InterruptedException {
        Rig r = rig(10);
        r.queues().onSealedBar(bar(60));
        r.sink().armed.add(retryable("bar write failed"));
        r.sink().recoverRefusals = 3; // the database stays down through three retries

        r.pump().cycle();

        assertEquals(List.of(5L, 10L, 15L, 23L), r.sink().recoverCalledAtSeconds,
                "playbook §8 backoff: 1s base doubling under a 5s floor — 5, 5, 5, then 8");
        assertEquals(4, r.events().events.get(0).detail().get("attempts").asInt());
        assertEquals(23_000, r.events().events.get(0).detail().get("outageMs").asLong());
        assertEquals(1, r.pump().sinkFailures(), "one episode, however many retries");
    }

    @Test
    void aTerminalSinkFailureStopsThePumpAtOnceAndReportsTheDeath() {
        Rig r = rig(10);
        r.queues().onSealedBar(bar(60));
        PersistenceException boom = terminal("unique violation");
        r.sink().armed.add(boom);

        r.pump().run();

        assertSame(boom, r.pump().failure());
        assertEquals(List.of(boom), r.deaths(), "the runner hears it now, not a heartbeat later");
        assertTrue(r.sink().recoverCalledAtSeconds.isEmpty(), "never retry what we cannot name");
        assertNotNull(r.queues().peekBarNow(), "ack-after-apply: the bar stays queued");
        ServiceEvent death = r.events().events.get(0);
        assertEquals(EventType.DB_ERROR, death.type(), "the console learns why capture died");
        assertTrue(death.detail().get("cause").asText().contains("unique violation"));
        assertEquals(1, death.detail().get("queued").asInt());
    }

    @Test
    void aFailingDeathEventStillReportsTheDeath() {
        FakeClock clock = new FakeClock();
        Buffers queues = new Buffers(10, () -> clock.nanos);
        FlakySink sink = new FlakySink(clock);
        queues.onSealedBar(bar(60));
        PersistenceException boom = terminal("unique violation");
        sink.armed.add(boom);
        EventLog down = event -> {
            throw new RuntimeException("service_events insert failed");
        };
        List<RuntimeException> deaths = new ArrayList<>();
        Pump pump = new Pump(queues, sink, new GapDetector(), new FakeGapStore(), down,
                new FakeSleeper(clock), clock, BACKOFF, LOG_NOWHERE, deaths::add);

        pump.run();

        assertEquals(List.of(boom), deaths, "the breadcrumb is Tier 2 — it never blocks the exit");
        assertEquals(1, pump.observabilityFailures());
    }

    @Test
    void aTerminalFailureDuringRecoveryStopsThePumpAtOnce() {
        Rig r = rig(10);
        r.queues().onSealedBar(bar(60));
        r.sink().armed.add(retryable("bar write failed"));
        PersistenceException boom = terminal("relation ticks does not exist"); // back, without our table
        r.sink().recoverFailure = boom;

        r.pump().run();

        assertSame(boom, r.pump().failure());
        assertEquals(List.of(boom), r.deaths());
        assertEquals(1, r.sink().recoverCalledAtSeconds.size(), "one attempt — never retry what we cannot name");
        assertTrue(r.events().events.stream().noneMatch(e -> e.type() == EventType.SINK_FAILURE),
                "no recovery happened, so no recovery is recorded");
    }

    @Test
    void anIdleFlushBlipHoldsLikeAnyOther() throws InterruptedException {
        Rig r = rig(10); // nothing queued — the quiet-market case
        r.sink().armed.add(retryable("tick batch flush failed"));

        r.pump().cycle(); // idle → the flush fails → hold → recover at 5s
        r.pump().cycle(); // idle again → the flush succeeds

        assertEquals(List.of(5L), r.sink().recoverCalledAtSeconds);
        assertEquals(1, r.pump().sinkFailures());
        assertEquals(1, r.sink().flushes, "the retried flush is the first that counts");
        assertTrue(r.deaths().isEmpty());
    }

    @Test
    void aTickWriteBlipHoldsAndTheStreamResumes() throws InterruptedException {
        Rig r = rig(10);
        r.queues().onTick(tick(10));
        r.sink().armed.add(retryable("tick write failed")); // the store holds the tick; the pump holds the line

        r.pump().cycle(); // the write fails → hold → recover at 5s
        r.queues().onTick(tick(11));
        r.pump().cycle();

        assertEquals(List.of(5L), r.sink().recoverCalledAtSeconds);
        assertEquals(1, r.pump().sinkFailures());
        assertEquals(List.of("tick@11"), r.sink().order,
                "capture resumes — the failed tick is the store's to re-send (chapter 5)");
        assertTrue(r.deaths().isEmpty());
    }

    @Test
    void stopDuringAHoldEndsItAtTheNextSliceWithoutADeath() {
        Rig r = rig(10);
        r.queues().onSealedBar(bar(60));
        r.sink().armed.add(retryable("bar write failed"));
        r.sink().armed.add(retryable("still down at shutdown")); // the tail drain meets a dead sink
        r.sink().recoverRefusals = Integer.MAX_VALUE; // the database never comes back
        r.sleeper().onSleep = () -> {
            if (r.sleeper().sleeps == 7) {
                r.pump().stop(); // Ctrl-C mid-wait
            }
        };

        r.pump().run();

        assertEquals(7, r.sleeper().sleeps,
                "stop() is honoured at the next 250ms slice, not at the end of the 5s wait");
        assertTrue(r.sink().recoverCalledAtSeconds.isEmpty());
        assertNotNull(r.pump().failure(), "the tail drain's failure is recorded for the summary");
        assertTrue(r.deaths().isEmpty(),
                "a stop is not a death — an exit from inside the shutdown hook would deadlock");
        assertNotNull(r.queues().peekBarNow());
    }

    @Test
    void aFailingRecoveryEventIsCountedNotThrown() throws InterruptedException {
        FakeClock clock = new FakeClock();
        FakeSleeper sleeper = new FakeSleeper(clock);
        Buffers queues = new Buffers(10, () -> clock.nanos);
        FlakySink sink = new FlakySink(clock);
        queues.onSealedBar(bar(60));
        sink.armed.add(retryable("bar write failed"));
        EventLog boom = event -> {
            throw new RuntimeException("service_events insert failed");
        };
        Pump pump = new Pump(queues, sink, new GapDetector(), new FakeGapStore(), boom, sleeper,
                clock, BACKOFF, LOG_NOWHERE, NO_DEATH);

        pump.cycle();
        pump.cycle();

        assertEquals(List.of("bar@60"), sink.order);
        assertEquals(1, pump.observabilityFailures(), "the breadcrumb is Tier 2 — counted, never thrown");
    }

    @Test
    void aHoldThatOutlivesTheTickQueueIsAnnouncedAsLossy() throws InterruptedException {
        Rig r = rig(2); // a tiny tick queue: shedding starts on the third tick
        for (int i = 0; i < 5; i++) {
            r.queues().onTick(tick(100 + i)); // 3 shed before the outage — the episode must not count them
        }
        r.queues().onSealedBar(bar(60));
        r.sink().armed.add(retryable("bar write failed"));
        r.sleeper().onSleep = () -> r.queues().onTick(tick(r.sleeper().sleeps)); // ticks keep arriving

        r.pump().cycle();

        assertEquals(23, r.queues().droppedTicks(), "3 before + 20 during the 5s hold into a full queue");
        assertTrue(r.log().stream().anyMatch(line -> line.contains("lossy")),
                "the moment the hold stops being lossless is announced");
        assertEquals(20, r.events().events.get(0).detail().get("ticksShed").asLong(),
                "the episode's own shedding, not the lifetime count");
    }
}
