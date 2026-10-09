package dev.amfshr.tradebench.marketdata.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.ig.stream.FakeStreamTransport;
import dev.amfshr.tradebench.marketdata.events.EventType;
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

    @Test
    void theSupervisorsEventsReachTheLogOnlyThroughTheWriter() {
        // D29 (3), E1-T10 #12: nothing the sweep records is written on the sweep thread. It is
        // queued, and lands when the writer takes its turn — here, when the test drains in its place.
        RecordingEventLog log = new RecordingEventLog();
        AtomicBoolean exhausted = new AtomicBoolean();
        FakeClock clock = new FakeClock();
        CaptureAssembly capture = CaptureAssembly.compose(new CaptureAssembly.Ports("test",
                List.of("IX.D.DAX.DAILY.IP"), clock, new FakeSleeper(clock), () -> 1.0, Tuning.playbook(),
                new FakeSessions(), new FakeStreamTransport(), new ScriptedCaptureStore(), log,
                new RecordingGapStore(), new RecordingStatusStore(), message -> { },
                () -> exhausted.set(true), cause -> { }, Buffers.DEFAULT_TICK_CAPACITY));

        capture.supervisor.die(new IllegalStateException("a sweep that threw"));

        assertTrue(exhausted.get(), "the runner is told at once — the hand-over is not queued");
        assertTrue(log.written.isEmpty(), "but the row is: nothing is written on the sweep thread");
        assertEquals(1, capture.events.queued());
        assertEquals(1, capture.events.drainOnce());
        assertEquals(EventType.FEED_DEAD, log.written.get(0).type());
    }
}
