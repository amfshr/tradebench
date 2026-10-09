package dev.amfshr.tradebench.marketdata.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.marketdata.events.EventType;
import dev.amfshr.tradebench.marketdata.events.ServiceEvent;
import dev.amfshr.tradebench.marketdata.testutil.RecordingEventLog;

/** D29 (3): the sweep hands its events to a bounded queue; one writer takes them to the database. */
class QueuedEventLogTest {

    private final RecordingEventLog real = new RecordingEventLog();

    private static ServiceEvent event(EventType type, int second) {
        return ServiceEvent.of(type, Instant.parse("2026-09-28T09:00:00Z").plusSeconds(second));
    }

    @Test
    void eventsAreHandedOnInOrderWhenTheWriterTakesItsTurn() {
        QueuedEventLog queued = new QueuedEventLog(real, 10);
        queued.write(event(EventType.RECONNECT, 1));
        queued.write(event(EventType.WATCHDOG_STALE, 2));
        assertTrue(real.written.isEmpty(), "nothing reaches the database on the sweep thread");
        assertEquals(2, queued.queued());

        assertEquals(2, queued.drainOnce());

        assertEquals(List.of(EventType.RECONNECT, EventType.WATCHDOG_STALE),
                real.written.stream().map(ServiceEvent::type).toList());
        assertEquals(0, queued.queued());
    }

    @Test
    void aFullQueueRefusesTheEventSoTheCallerCountsTheDrop() {
        QueuedEventLog queued = new QueuedEventLog(real, 2);
        queued.write(event(EventType.RECONNECT, 1));
        queued.write(event(EventType.RECONNECT, 2));

        assertThrows(IllegalStateException.class, () -> queued.write(event(EventType.RECONNECT, 3)),
                "refused at once — the sweep never waits for room");

        assertEquals(2, queued.drainOnce(), "the two that fit");
    }

    @Test
    void aWriteTheLogRefusesIsCountedAndDroppedAndTheRestGoOn() {
        QueuedEventLog queued = new QueuedEventLog(real, 10);
        queued.write(event(EventType.RECONNECT, 1));
        queued.write(event(EventType.RECONNECT, 2));
        real.failWrites = true; // Postgres is away while the writer takes its turn
        assertEquals(2, queued.drainOnce(), "both attempted");
        assertEquals(2, queued.writeFailures(), "both counted, neither retried");
        assertTrue(real.written.isEmpty());

        real.failWrites = false;
        queued.write(event(EventType.RECONNECT, 3));
        queued.drainOnce();
        assertEquals(1, real.written.size(), "the next one lands");
    }

    @Test
    void theWriterThreadDeliversAsEventsArriveAndStopsWhenAsked() throws Exception {
        QueuedEventLog queued = new QueuedEventLog(real, 10);
        Thread writer = new Thread(queued, "capture-events");
        writer.start();
        queued.write(event(EventType.RECONNECT, 1));
        queued.write(event(EventType.RECONNECT, 2));
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (real.written.size() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(2, real.written.size(), "delivered by the writer, not the caller");

        queued.stop();
        writer.join(2_000);
        assertFalse(writer.isAlive(), "stopped after its poll");
    }
}
