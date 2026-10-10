package dev.amfshr.tradebench.marketdata.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.ig.stream.FakeStreamTransport;
import dev.amfshr.tradebench.ig.stream.TickUpdate;
import dev.amfshr.tradebench.marketdata.events.EventType;
import dev.amfshr.tradebench.marketdata.events.ServiceEvent;
import dev.amfshr.tradebench.marketdata.ingest.Buffers;
import dev.amfshr.tradebench.marketdata.supervise.Tuning;
import dev.amfshr.tradebench.marketdata.testutil.FakeClock;
import dev.amfshr.tradebench.marketdata.testutil.FakeSessions;
import dev.amfshr.tradebench.marketdata.testutil.FakeSleeper;
import dev.amfshr.tradebench.marketdata.testutil.RecordingEventLog;
import dev.amfshr.tradebench.marketdata.testutil.RecordingGapStore;
import dev.amfshr.tradebench.marketdata.testutil.RecordingStatusStore;
import dev.amfshr.tradebench.marketdata.testutil.ScriptedCaptureStore;

/** The composition root's own promises — what the harness exercises but cannot see. */
class CaptureAssemblyTest {

    private static final String DAX = "IX.D.DAX.DAILY.IP";

    private final RecordingEventLog log = new RecordingEventLog();
    private final AtomicBoolean exhausted = new AtomicBoolean();

    private CaptureAssembly compose() {
        FakeClock clock = new FakeClock();
        return CaptureAssembly.compose(new CaptureAssembly.Ports("test", List.of(DAX), clock,
                new FakeSleeper(clock), () -> 1.0, Tuning.playbook(), new FakeSessions(),
                new FakeStreamTransport(), new ScriptedCaptureStore(), log, new RecordingGapStore(),
                new RecordingStatusStore(), message -> { }, () -> exhausted.set(true), cause -> { },
                Buffers.DEFAULT_TICK_CAPACITY));
    }

    @Test
    void theSupervisorsEventsReachTheLogOnlyThroughTheWriter() {
        // D29 (3), E1-T10 #12: nothing the sweep records is written on the sweep thread. It is
        // queued, and lands when the writer takes its turn — here, when the test drains in its place.
        CaptureAssembly capture = compose();

        capture.supervisor.die(new IllegalStateException("a sweep that threw"));

        assertTrue(exhausted.get(), "the runner is told at once — the hand-over is not queued");
        assertTrue(log.written.isEmpty(), "but the row is: nothing is written on the sweep thread");
        assertEquals(1, capture.events.queued());
        assertEquals(1, capture.events.drainOnce());
        assertEquals(EventType.FEED_DEAD, log.written.get(0).type());
    }

    @Test
    void theHeartbeatCountsThePumpsRefusedEventsAsObsFailures() throws InterruptedException {
        // E1-T10 #26: the pump's log is best-effort by type; what it swallows must still reach the
        // heartbeat under D27's word for the pump's Tier-2 failures.
        CaptureAssembly capture = compose();
        log.failWrites = true;
        capture.queues.onTick(new TickUpdate(DAX, Instant.parse("2026-09-28T09:00:00Z"),
                BigDecimal.ONE, BigDecimal.TWO, "DEAL"));

        capture.pump.cycle(); // the tick lands; its DEAL flag is a state change whose row is refused

        assertTrue(capture.summary().contains(" obsFailures=1 "), capture.summary());
        assertNull(capture.pump.failure(), "a breadcrumb never halts capture");
    }

    @Test
    void theHeartbeatCountsTheBeltsRefusedEventsAsEventWriteFailures() {
        // A full queue refuses the sweep's event (D29 (3)); the belt's decorator counts it, and the
        // heartbeat sums it with the writer's own refusals.
        CaptureAssembly capture = compose();
        for (int i = 0; i < CaptureAssembly.EVENT_QUEUE_CAPACITY; i++) {
            capture.events.write(ServiceEvent.of(EventType.RECONNECT, Instant.EPOCH)); // the writer never ran
        }

        capture.supervisor.die(new IllegalStateException("a sweep that threw")); // FEED_DEAD meets a full queue

        assertTrue(capture.summary().contains(" eventWriteFailures=1 "), capture.summary());
    }
}
