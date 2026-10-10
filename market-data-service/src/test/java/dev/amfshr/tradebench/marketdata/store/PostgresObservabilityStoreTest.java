package dev.amfshr.tradebench.marketdata.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

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
        // 'ig-stream-live' seeds to id 2 (default-user is id 1), so a user/source swap or a
        // dropped dimension is value-detectable, not hidden by an id collision.
        store = new PostgresObservabilityStore(database.dataSource(), "default-user",
                "ig-stream-live", "test-run");
    }

    @Test
    void marketEventRecordsCategorySeverityDimensionsAndDetail() throws SQLException {
        store.write(ServiceEvent.of(EventType.RECONNECT, Instant.parse("2026-09-28T09:15:00Z"))
                .forEpic(DAX)
                .withDetail(MAPPER.createObjectNode().put("attempt", 3)));

        try (Connection c = database.dataSource().getConnection();
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT e.event_type, e.category, e.severity,"
                        + " e.instance, u.name AS usr, src.name AS source, i.epic AS market,"
                        + " e.detail->>'attempt' AS attempt, e.event_time_utc"
                        + " FROM service_events e JOIN users u ON e.user_id = u.id"
                        + " LEFT JOIN sources src ON e.source_id = src.id"
                        + " LEFT JOIN instruments i ON e.instrument_id = i.id")) {
            r.next();
            // Expectations hand-derived from the ruled catalogue (D25), not from the enum.
            assertEquals("reconnect", r.getString("event_type"));
            assertEquals("resilience", r.getString("category"));
            assertEquals("warn", r.getString("severity"));
            assertEquals("test-run", r.getString("instance"));
            assertEquals("default-user", r.getString("usr"));
            assertEquals("ig-stream-live", r.getString("source"));
            assertEquals(DAX, r.getString("market"));
            assertEquals("3", r.getString("attempt"));
            assertEquals(Instant.parse("2026-09-28T09:15:00Z"), instant(r, "event_time_utc"));
        }
    }

    @Test
    void globalEventHasNoMarketButKeepsUserAndSource() throws SQLException {
        store.write(ServiceEvent.of(EventType.DB_ERROR, Instant.parse("2026-09-28T09:16:00Z")));

        try (Connection c = database.dataSource().getConnection();
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT e.category, e.severity, u.name AS usr,"
                        + " src.name AS source, e.instrument_id FROM service_events e"
                        + " JOIN users u ON e.user_id = u.id"
                        + " LEFT JOIN sources src ON e.source_id = src.id")) {
            r.next();
            assertEquals("error", r.getString("category"));
            assertEquals("error", r.getString("severity"));
            assertEquals("default-user", r.getString("usr"));
            assertEquals("ig-stream-live", r.getString("source"));
            assertNull(r.getObject("instrument_id"));
        }
    }

    @Test
    void gapPersistsOpenWithSpanDimensionsAndDedupes() throws SQLException {
        GapDetector.Gap gap = new GapDetector.Gap(DAX, Instant.parse("2026-09-28T09:01:00Z"),
                Instant.parse("2026-09-28T09:03:00Z"), 3);
        store.record(gap);
        store.record(gap); // a re-detected span must not create a second row

        try (Connection c = database.dataSource().getConnection();
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT count(*) AS n, max(missing_minutes) AS mins,"
                        + " count(*) FILTER (WHERE healed_at_utc IS NULL) AS open,"
                        + " min(gap_from_utc) AS gfrom, min(gap_to_utc) AS gto,"
                        + " max(u.name) AS usr, max(src.name) AS source FROM bar_gaps g"
                        + " JOIN users u ON g.user_id = u.id JOIN sources src ON g.source_id = src.id")) {
            r.next();
            assertEquals(1, r.getInt("n"));
            assertEquals(3, r.getInt("mins"));
            assertEquals(1, r.getInt("open")); // healing is T6's
            assertEquals(Instant.parse("2026-09-28T09:01:00Z"), instant(r, "gfrom"));
            assertEquals(Instant.parse("2026-09-28T09:03:00Z"), instant(r, "gto"));
            assertEquals("default-user", r.getString("usr"));
            assertEquals("ig-stream-live", r.getString("source"));
        }
    }

    @Test
    void statusUpsertReplacesEveryFieldLatestWins() throws SQLException {
        // Two snapshots differing in EVERY field, so each DO UPDATE SET column is load-bearing.
        store.upsert(List.of(new CaptureStatus("test-run", DAX, Instant.parse("2026-09-28T09:20:00Z"),
                StreamState.CONNECTED_STREAMING, "DEAL", Instant.parse("2026-09-28T09:19:59Z"),
                Instant.parse("2026-09-28T09:19:00Z"), 10, 2, 1, 0, 0, 5)));
        store.upsert(List.of(new CaptureStatus("test-run", DAX, Instant.parse("2026-09-28T09:21:00Z"),
                StreamState.RECONNECTING, "CLOSED", Instant.parse("2026-09-28T09:20:30Z"),
                Instant.parse("2026-09-28T09:20:00Z"), 25, 4, 3, 1, 2, 7)));

        try (Connection c = database.dataSource().getConnection();
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT count(*) OVER () AS n, updated_at_utc,"
                        + " stream_state, market_state, last_tick_at_utc, last_bar_at_utc,"
                        + " ticks_total, bars_total, dropped_ticks, malformed, reconnects_total,"
                        + " db_pending FROM capture_status")) {
            r.next();
            assertEquals(1, r.getInt("n")); // the upsert replaced, not appended
            assertEquals(Instant.parse("2026-09-28T09:21:00Z"), instant(r, "updated_at_utc"));
            assertEquals("reconnecting", r.getString("stream_state"));
            assertEquals("CLOSED", r.getString("market_state"));
            assertEquals(Instant.parse("2026-09-28T09:20:30Z"), instant(r, "last_tick_at_utc"));
            assertEquals(Instant.parse("2026-09-28T09:20:00Z"), instant(r, "last_bar_at_utc"));
            assertEquals(25, r.getLong("ticks_total"));
            assertEquals(4, r.getLong("bars_total"));
            assertEquals(3, r.getLong("dropped_ticks"));
            assertEquals(1, r.getLong("malformed"));
            assertEquals(2, r.getLong("reconnects_total"));
            assertEquals(7, r.getInt("db_pending"));
        }
    }

    @Test
    void statusUpsertStoresNullDbPending() throws SQLException {
        store.upsert(List.of(new CaptureStatus("test-run", DAX, Instant.parse("2026-09-28T09:22:00Z"),
                StreamState.WINDOW_CLOSED, null, null, null, 0, 0, 0, 0, 0, null)));

        try (Connection c = database.dataSource().getConnection();
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT db_pending FROM capture_status")) {
            r.next();
            assertNull(r.getObject("db_pending"));
        }
    }

    @Test
    void statusUpsertLandsEveryMarketOfTheBatch() throws SQLException {
        // E1-T10 #22: the heartbeat sends every market's row in one call — each must land.
        store.upsert(List.of(
                new CaptureStatus("test-run", DAX, Instant.parse("2026-09-28T09:23:00Z"),
                        StreamState.CONNECTED_STREAMING, "DEAL", null, null, 11, 1, 0, 0, 0, 0),
                new CaptureStatus("test-run", "IX.D.FTSE.DAILY.IP", Instant.parse("2026-09-28T09:23:00Z"),
                        StreamState.CONNECTED_STREAMING, "CLOSED", null, null, 22, 2, 0, 0, 0, 0)));

        try (Connection c = database.dataSource().getConnection();
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT i.epic, cs.ticks_total FROM capture_status cs"
                        + " JOIN instruments i ON cs.instrument_id = i.id ORDER BY i.epic")) {
            r.next();
            assertEquals(DAX, r.getString("epic"));
            assertEquals(11, r.getLong("ticks_total"));
            r.next();
            assertEquals("IX.D.FTSE.DAILY.IP", r.getString("epic"));
            assertEquals(22, r.getLong("ticks_total"));
            assertEquals(false, r.next(), "two markets, two rows");
        }
    }

    private static Instant instant(ResultSet r, String column) throws SQLException {
        return r.getObject(column, OffsetDateTime.class).toInstant();
    }
}
