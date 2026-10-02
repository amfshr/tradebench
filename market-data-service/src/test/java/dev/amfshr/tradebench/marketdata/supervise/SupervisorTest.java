package dev.amfshr.tradebench.marketdata.supervise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.core.time.Clock;
import dev.amfshr.tradebench.ig.time.Sleeper;
import dev.amfshr.tradebench.marketdata.events.EventType;
import dev.amfshr.tradebench.marketdata.events.ServiceEvent;
import dev.amfshr.tradebench.marketdata.events.Severity;
import dev.amfshr.tradebench.marketdata.store.EventLog;

class SupervisorTest {

    private static final String STREAMING = "CONNECTED:WS-STREAMING";
    private static final String WILL_RETRY = "DISCONNECTED:WILL-RETRY";
    private static final String TRYING_RECOVERY = "DISCONNECTED:TRYING-RECOVERY";

    private FakeClock clock;
    private RecordingEvents events;
    private FakeStream stream;
    private AtomicBoolean exhausted;
    private Supervisor supervisor;

    @BeforeEach
    void setUp() {
        clock = new FakeClock(Instant.parse("2026-09-28T09:00:00Z"));
        events = new RecordingEvents();
        stream = new FakeStream();
        exhausted = new AtomicBoolean();
        // jitter = 1.0 → deterministic backoff; Sleeper/interval unused (tests drive sweep() directly).
        supervisor = new Supervisor(clock, Tuning.playbook(), () -> 1.0, events, stream,
                () -> exhausted.set(true), Sleeper.SYSTEM, Duration.ofSeconds(1));
    }

    @Test
    void replayedReconnectIsInfoAndResolvesNoGap() {
        supervisor.onStatusChange(TRYING_RECOVERY);
        clock.advance(Duration.ofSeconds(30));
        supervisor.onStatusChange(STREAMING);
        supervisor.sweep();

        ServiceEvent reconnect = single(EventType.RECONNECT);
        assertEquals(Severity.INFO, reconnect.severity());
        assertTrue(reconnect.detail().get("replayed").asBoolean());
        assertEquals(0, stream.rebuilds); // a replayed resume needs no rebuild
    }

    @Test
    void reconnectAfterWillRetryIsWarnAndFlagsAGap() {
        supervisor.onStatusChange(WILL_RETRY);
        clock.advance(Duration.ofSeconds(30));
        supervisor.onStatusChange(STREAMING);
        supervisor.sweep();

        ServiceEvent reconnect = single(EventType.RECONNECT);
        assertEquals(Severity.WARN, reconnect.severity());
        assertFalse(reconnect.detail().get("replayed").asBoolean()); // the outage's data is owed a heal
    }

    @Test
    void stuckSubstateForcesARebuildOnlyPastTheThreshold() {
        supervisor.onStatusChange(WILL_RETRY);
        supervisor.sweep();
        assertEquals(0, stream.rebuilds); // not yet — inside the 120s will-retry window

        clock.advance(Duration.ofSeconds(120));
        supervisor.sweep();
        assertEquals(1, stream.rebuilds);
        assertEquals(1, count(EventType.STUCK_SUBSTATE_ESCALATED));
    }

    @Test
    void rebuildsHoldOffWithinTheBackoffWindow() {
        supervisor.onStatusChange(WILL_RETRY);
        supervisor.sweep();
        clock.advance(Duration.ofSeconds(120));
        supervisor.sweep(); // first rebuild; next is spaced by delayFor(1) = 5s (floor)
        assertEquals(1, stream.rebuilds);

        clock.advance(Duration.ofSeconds(3)); // still inside the 5s window
        supervisor.sweep();
        assertEquals(1, stream.rebuilds); // paced — no second rebuild

        clock.advance(Duration.ofSeconds(5)); // now past the window
        supervisor.sweep();
        assertEquals(2, stream.rebuilds);
    }

    @Test
    void rebuildsAreBackoffPacedThenGiveUpLoud() {
        int ceiling = Tuning.playbook().maxConsecutiveFailures();
        supervisor.onStatusChange(WILL_RETRY);
        supervisor.sweep(); // records the stuck substate

        for (int attempt = 0; attempt < ceiling; attempt++) {
            clock.advance(Duration.ofSeconds(120)); // clears both the threshold and any backoff spacing
            supervisor.sweep();
        }
        assertEquals(ceiling, stream.rebuilds);
        assertFalse(exhausted.get());

        clock.advance(Duration.ofSeconds(120));
        supervisor.sweep();
        assertTrue(exhausted.get());
        assertEquals(ceiling, stream.rebuilds); // no rebuild past the ceiling
        assertEquals(1, count(EventType.FEED_DEAD));
    }

    @Test
    void gracefulCloseHushesTheFarewellDisconnect() {
        supervisor.closing();
        supervisor.onStatusChange(WILL_RETRY);
        clock.advance(Duration.ofSeconds(30));
        supervisor.onStatusChange(STREAMING); // a resume that WOULD emit a reconnect, were it not hushed
        supervisor.sweep();

        assertTrue(ofType(EventType.RECONNECT).isEmpty()); // hushed: closing() meant no outage was opened
        assertEquals(0, stream.rebuilds);
    }

    @Test
    void hostSleepIsAnnotatedNotCountedAsAWildOutage() {
        supervisor.onStatusChange(TRYING_RECOVERY);
        clock.advanceWallOnly(Duration.ofMinutes(16)); // lid closed — wall jumps, monotonic frozen
        clock.advance(Duration.ofMillis(400));         // a sliver of real elapsed time on wake
        supervisor.onStatusChange(STREAMING);
        supervisor.sweep();

        ServiceEvent reconnect = single(EventType.RECONNECT);
        assertTrue(reconnect.detail().get("hostSlept").asBoolean());
        assertTrue(reconnect.detail().get("replayed").asBoolean());
    }

    // --- helpers + fakes --------------------------------------------------------------------

    private List<ServiceEvent> ofType(EventType type) {
        List<ServiceEvent> matches = new ArrayList<>();
        for (ServiceEvent event : events.written) {
            if (event.type() == type) {
                matches.add(event);
            }
        }
        return matches;
    }

    private int count(EventType type) {
        return ofType(type).size();
    }

    private ServiceEvent single(EventType type) {
        List<ServiceEvent> matches = ofType(type);
        assertEquals(1, matches.size(), "expected exactly one " + type);
        return matches.get(0);
    }

    private static final class FakeClock implements Clock {
        private long monotonicNanos;
        private Instant wall;

        FakeClock(Instant start) {
            this.wall = start;
        }

        @Override
        public Instant wallInstant() {
            return wall;
        }

        @Override
        public long monotonicNanos() {
            return monotonicNanos;
        }

        void advance(Duration by) {
            monotonicNanos += by.toNanos();
            wall = wall.plus(by);
        }

        void advanceWallOnly(Duration by) {
            wall = wall.plus(by); // monotonic is frozen while the host sleeps
        }
    }

    private static final class RecordingEvents implements EventLog {
        final List<ServiceEvent> written = new ArrayList<>();

        @Override
        public void write(ServiceEvent event) {
            written.add(event);
        }
    }

    private static final class FakeStream implements StreamControl {
        int rebuilds;

        @Override
        public void rebuild() {
            rebuilds++;
        }
    }
}
