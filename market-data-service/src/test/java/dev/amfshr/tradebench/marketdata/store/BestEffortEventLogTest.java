package dev.amfshr.tradebench.marketdata.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.marketdata.events.EventType;
import dev.amfshr.tradebench.marketdata.events.ServiceEvent;
import dev.amfshr.tradebench.marketdata.testutil.RecordingEventLog;

class BestEffortEventLogTest {
    private static final ServiceEvent EVENT =
            ServiceEvent.of(EventType.IG_API_ERROR, Instant.parse("2026-09-28T09:00:00Z"));

    private final RecordingEventLog store = new RecordingEventLog();
    private final List<String> log = new ArrayList<>();
    private final BestEffortEventLog events = new BestEffortEventLog(store, log::add);

    @Test
    void aWriteThatLandsIsPassedThroughUncounted() {
        events.write(EVENT);

        assertEquals(List.of(EVENT), store.written);
        assertEquals(0, events.failures());
        assertTrue(log.isEmpty());
    }

    @Test
    void aRefusedWriteIsCountedNeverThrown() {
        store.failWrites = true; // Postgres is down — the breadcrumb cannot be written

        events.write(EVENT); // must not throw — whoever is writing must survive (D27 Tier 2)
        events.write(EVENT);

        assertEquals(2, events.failures(), "counted — loud, not silent");
        assertTrue(store.written.isEmpty());
    }

    @Test
    void aRefusedWriteIsLoggedWithItsTypeAndCause() {
        store.failWrites = true;

        events.write(EVENT);

        assertEquals(1, log.size());
        assertEquals("event not written (IG_API_ERROR): java.lang.IllegalStateException:"
                + " service_events unavailable", log.get(0));
    }
}
