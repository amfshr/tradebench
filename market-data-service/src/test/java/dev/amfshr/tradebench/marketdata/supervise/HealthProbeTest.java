package dev.amfshr.tradebench.marketdata.supervise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.core.time.Clock;
import dev.amfshr.tradebench.marketdata.events.CaptureStatus;
import dev.amfshr.tradebench.marketdata.events.StreamState;
import dev.amfshr.tradebench.marketdata.store.StatusStore;

class HealthProbeTest {

    private static final String DAX = "IX.D.DAX.DAILY.IP";
    private static final String FTSE = "IX.D.FTSE.DAILY.IP";
    private static final Instant NOW = Instant.parse("2026-09-28T09:20:00Z");

    private static final class FakeTelemetry implements MarketTelemetry {
        final Map<String, MarketCounts> counts = new HashMap<>();
        final Map<String, String> flags = new HashMap<>();
        int pending;

        @Override
        public MarketCounts countsFor(String epic) {
            return counts.getOrDefault(epic, MarketCounts.NONE);
        }

        @Override
        public int pendingWrites() {
            return pending;
        }

        @Override
        public long lastTickMono(String epic) {
            return Long.MIN_VALUE;
        }

        @Override
        public long lastBarMono(String epic) {
            return Long.MIN_VALUE;
        }

        @Override
        public @Nullable String dealFlag(String epic) {
            return flags.get(epic);
        }
    }

    private static final class FakeBelt implements BeltView {
        final Map<String, StreamState> states = new HashMap<>();
        long reconnects;

        @Override
        public StreamState stateOf(String epic) {
            return states.getOrDefault(epic, StreamState.RECONNECTING);
        }

        @Override
        public long reconnectsTotal() {
            return reconnects;
        }
    }

    private static final class RecordingStore implements StatusStore {
        final List<CaptureStatus> rows = new ArrayList<>();
        boolean fail;

        @Override
        public void upsert(CaptureStatus status) {
            if (fail) {
                throw new IllegalStateException("capture_status unavailable");
            }
            rows.add(status);
        }
    }

    private static final Clock FROZEN = new Clock() {
        @Override
        public Instant wallInstant() {
            return NOW;
        }

        @Override
        public long monotonicNanos() {
            return 0;
        }
    };

    private final FakeTelemetry telemetry = new FakeTelemetry();
    private final FakeBelt belt = new FakeBelt();
    private final RecordingStore store = new RecordingStore();
    private final HealthProbe probe =
            new HealthProbe("test-run", List.of(DAX, FTSE), telemetry, belt, FROZEN, store);

    @Test
    void snapshotAssemblesEveryColumnFromItsSource() {
        // Every value distinct, so a swapped or dropped column cannot hide (the slice-B upsert trick).
        telemetry.counts.put(DAX, new MarketTelemetry.MarketCounts(10, 2, 1, 4,
                Instant.parse("2026-09-28T09:19:59Z"), Instant.parse("2026-09-28T09:19:00Z")));
        telemetry.flags.put(DAX, "DEAL");
        telemetry.pending = 7;
        belt.states.put(DAX, StreamState.CONNECTED_STREAMING);
        belt.reconnects = 3;

        CaptureStatus row = probe.snapshot(DAX);

        assertEquals(new CaptureStatus("test-run", DAX, NOW, StreamState.CONNECTED_STREAMING, "DEAL",
                Instant.parse("2026-09-28T09:19:59Z"), Instant.parse("2026-09-28T09:19:00Z"),
                10, 2, 1, 4, 3, 7), row);
    }

    @Test
    void anUnseenMarketIsAnHonestRowOfZerosAndNulls() {
        telemetry.pending = 0;

        CaptureStatus row = probe.snapshot(FTSE);

        assertEquals(StreamState.RECONNECTING, row.streamState());
        assertNull(row.marketState());
        assertNull(row.lastTickAtUtc());
        assertNull(row.lastBarAtUtc());
        assertEquals(0, row.ticksTotal());
        assertEquals(0, row.barsTotal());
        assertEquals(0, row.dbPending());
    }

    @Test
    void publishUpsertsOneRowPerMarketInOrder() {
        probe.publish();

        assertEquals(List.of(DAX, FTSE), store.rows.stream().map(CaptureStatus::epic).toList());
        assertEquals(0, probe.statusFailures());
    }

    @Test
    void aFailingUpsertIsCountedNeverThrown() {
        store.fail = true;

        probe.publish(); // must not throw — the heartbeat thread must survive

        assertEquals(2, probe.statusFailures(), "one per market, loud not silent");
        store.fail = false;
        probe.publish();
        assertEquals(2, store.rows.size(), "the store heals on the next heartbeat");
        assertEquals(2, probe.statusFailures());
    }
}
