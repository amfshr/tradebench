package dev.amfshr.tradebench.marketdata.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.amfshr.tradebench.marketdata.coverage.GapDetector;
import dev.amfshr.tradebench.marketdata.events.CaptureStatus;
import dev.amfshr.tradebench.marketdata.events.EventType;
import dev.amfshr.tradebench.marketdata.events.ServiceEvent;
import dev.amfshr.tradebench.marketdata.events.StreamState;

class PostgresObservabilityStoreTest extends PostgresTestBase {

    private static final String DAX = "IX.D.DAX.DAILY.IP";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PostgresObservabilityStore store;

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection c = database.dataSource().getConnection();
                Statement s = c.createStatement()) {
            s.execute("TRUNCATE service_events, bar_gaps, capture_status, instruments"
                    + " RESTART IDENTITY CASCADE");
        }
        store = new PostgresObservabilityStore(database.dataSource(), "default-user",
                "ig-stream-demo", "test-run");
    }

    @Test
    void marketEventRecordsCategorySeverityDimensionsAndDetail() throws SQLException {
        store.write(ServiceEvent.of(EventType.RECONNECT, Instant.parse("2026-09-28T09:15:00Z"))
                .forEpic(DAX)
                .withDetail(MAPPER.createObjectNode().put("attempt", 3)));

        try (Connection c = database.dataSource().getConnection();
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT event_type, category, severity, instance,"
                        + " instrument_id IS NOT NULL AS has_market, detail->>'attempt',"
                        + " event_time_utc FROM service_events")) {
            r.next();
            // Expectations hand-derived from the ruled catalogue (D25), not from the enum.
            assertEquals("reconnect", r.getString(1));
            assertEquals("resilience", r.getString(2));
            assertEquals("warn", r.getString(3));
            assertEquals("test-run", r.getString(4));
            assertEquals(true, r.getBoolean(5));
            assertEquals("3", r.getString(6));
            assertEquals(Instant.parse("2026-09-28T09:15:00Z"),
                    r.getObject(7, java.time.OffsetDateTime.class).toInstant());
        }
    }

    @Test
    void globalEventHasNoMarket() throws SQLException {
        store.write(ServiceEvent.of(EventType.DB_ERROR, Instant.parse("2026-09-28T09:16:00Z")));

        try (Connection c = database.dataSource().getConnection();
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery(
                        "SELECT category, severity, instrument_id FROM service_events")) {
            r.next();
            assertEquals("error", r.getString(1));
            assertEquals("error", r.getString(2));
            assertNull(r.getObject(3));
        }
    }

    @Test
    void gapPersistsOpenAndDedupesBySpan() throws SQLException {
        GapDetector.Gap gap = new GapDetector.Gap(DAX, Instant.parse("2026-09-28T09:01:00Z"),
                Instant.parse("2026-09-28T09:03:00Z"), 3);
        store.record(gap);
        store.record(gap); // a re-detected span must not create a second row

        try (Connection c = database.dataSource().getConnection();
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT count(*), max(missing_minutes),"
                        + " count(*) FILTER (WHERE healed_at_utc IS NULL) FROM bar_gaps")) {
            r.next();
            assertEquals(1, r.getInt(1));
            assertEquals(3, r.getInt(2));
            assertEquals(1, r.getInt(3)); // open — healing is T6's
        }
    }

    @Test
    void statusUpsertKeepsOneRowLatestWins() throws SQLException {
        store.upsert(status(10, StreamState.CONNECTED_STREAMING, "DEAL"));
        store.upsert(status(25, StreamState.RECONNECTING, "CLOSED"));

        try (Connection c = database.dataSource().getConnection();
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT count(*), max(ticks_total),"
                        + " max(stream_state), max(market_state) FROM capture_status")) {
            r.next();
            assertEquals(1, r.getInt(1)); // one row — the upsert replaced, not appended
            assertEquals(25, r.getLong(2));
            assertEquals("reconnecting", r.getString(3));
            assertEquals("CLOSED", r.getString(4));
        }
    }

    private static CaptureStatus status(long ticks, StreamState state, String marketState) {
        return new CaptureStatus("test-run", DAX, Instant.parse("2026-09-28T09:20:00Z"), state,
                marketState, Instant.parse("2026-09-28T09:19:59Z"), null, ticks, 4, 0, 0, 1, 0);
    }
}
