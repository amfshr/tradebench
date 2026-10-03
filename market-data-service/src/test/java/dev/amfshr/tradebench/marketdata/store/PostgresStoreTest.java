package dev.amfshr.tradebench.marketdata.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.core.domain.Bar1m;
import dev.amfshr.tradebench.core.domain.OhlcPrices;
import dev.amfshr.tradebench.core.domain.Tick;

class PostgresStoreTest extends PostgresTestBase {

    private static final String DAX = "IX.D.DAX.DAILY.IP";

    private PostgresStore sink;

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection c = database.dataSource().getConnection();
                Statement s = c.createStatement()) {
            s.execute("TRUNCATE ticks, bars_1m, instruments"
                    + " RESTART IDENTITY CASCADE");
        }
        sink = new PostgresStore(database.dataSource(), "default-user", "ig-stream-demo");
    }

    @AfterEach
    void tearDown() {
        sink.close();
    }

    private static Tick tick(String epic, String iso, String bid, String ask) {
        return new Tick(epic, Instant.parse(iso), new BigDecimal(bid), new BigDecimal(ask));
    }

    private static Bar1m bar(String iso, String bidClose, Long ltv) {
        return new Bar1m(DAX, Instant.parse(iso),
                new OhlcPrices(new BigDecimal("24510.5"), new BigDecimal("24514.8"),
                        new BigDecimal("24508.3"), new BigDecimal(bidClose)),
                new OhlcPrices(new BigDecimal("24511.7"), new BigDecimal("24516.0"),
                        new BigDecimal("24509.5"), new BigDecimal("24513.2")),
                ltv);
    }

    private long count(String table) throws SQLException {
        try (Connection c = database.dataSource().getConnection();
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT count(*) FROM " + table)) {
            r.next();
            return r.getLong(1);
        }
    }

    @Test
    void migrationSeedsTheDimensionRowsByName() throws SQLException {
        // Names anchored literally — T6's healer looks up 'ig-rest-heal' by string; a
        // seed rename must go red here, not in production (review F4).
        try (Connection c = database.dataSource().getConnection();
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery(
                        "SELECT string_agg(name, ',' ORDER BY name) FROM sources")) {
            r.next();
            assertEquals("ig-rest-heal,ig-stream-demo,ig-stream-live", r.getString(1));
        }
        try (Connection c = database.dataSource().getConnection();
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT string_agg(name, ',') FROM users")) {
            r.next();
            assertEquals("default-user", r.getString(1));
        }
    }

    @Test
    void tickBatchAutoFlushesExactlyAtTheLimit() throws SQLException {
        for (int i = 0; i < PostgresStore.TICK_BATCH_LIMIT - 1; i++) {
            sink.write(tick(DAX, "2026-09-28T07:00:0" + (i % 10) + "." + (100000 + i) + "Z",
                    "1." + i, "2.0"));
        }
        assertEquals(0, count("ticks"), "one below the limit: still batched");

        sink.write(tick(DAX, "2026-09-28T07:59:59Z", "9.9", "9.99"));

        assertEquals(PostgresStore.TICK_BATCH_LIMIT, count("ticks"),
                "the limit-th write must flush — under a busy stream this is the ONLY"
                        + " flush trigger (review F2)");
    }

    @Test
    void ticksLandOnFlushWithExactScalePreserved() throws SQLException {
        sink.write(tick(DAX, "2026-09-28T07:00:01.250Z", "24510.5", "24511.70"));
        assertEquals(0, count("ticks"), "ticks batch until flush — best-effort by design");

        sink.flush();

        try (Connection c = database.dataSource().getConnection();
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT bid, ask FROM ticks")) {
            r.next();
            assertEquals(new BigDecimal("24510.5"), r.getBigDecimal(1));
            assertEquals(new BigDecimal("24511.70"), r.getBigDecimal(2),
                    "numeric preserves the wire's scale (D36)");
        }
    }

    @Test
    void exactDuplicateTickDropsIdempotently() throws SQLException {
        sink.write(tick(DAX, "2026-09-28T07:00:01.250Z", "24510.5", "24511.7"));
        sink.write(tick(DAX, "2026-09-28T07:00:01.250Z", "24510.5", "24511.7"));
        sink.flush();

        assertEquals(1, count("ticks"), "redelivery/re-import must not double rows");
    }

    @Test
    void sameMillisecondDifferentPricesBothSurvive() throws SQLException {
        sink.write(tick(DAX, "2026-09-28T07:00:01.250Z", "24510.5", "24511.7"));
        sink.write(tick(DAX, "2026-09-28T07:00:01.250Z", "24511.0", "24512.2"));
        sink.flush();

        assertEquals(2, count("ticks"), "finest truth: same-ms distinct ticks are real");
    }

    @Test
    void barUpsertIsIdempotentAndHealOverwrites() throws SQLException {
        sink.write(bar("2026-09-28T07:00:00Z", "24512.0", 321L));
        sink.write(bar("2026-09-28T07:00:00Z", "24512.0", 321L));
        assertEquals(1, count("bars_1m"), "sealed-bar redelivery re-applies, never doubles");

        sink.write(bar("2026-09-28T07:00:00Z", "24512.5", 400L));
        try (Connection c = database.dataSource().getConnection();
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT bid_c, ltv FROM bars_1m")) {
            r.next();
            assertEquals(new BigDecimal("24512.5"), r.getBigDecimal(1),
                    "a heal overwrites in place (D36: heals recompute forward)");
            assertEquals(400L, r.getLong(2));
        }
    }

    // Change detector (doctrine 2.3): registration correctness; the id-cache is an
    // optimisation with no observable row effect, so no mutation can kill this via rows.
    @Test
    void instrumentRegistersOnceHoweverManyRows() throws SQLException {
        sink.write(tick(DAX, "2026-09-28T07:00:01Z", "1.0", "2.0"));
        sink.write(tick(DAX, "2026-09-28T07:00:02Z", "1.0", "2.0"));
        sink.write(bar("2026-09-28T07:00:00Z", "24512.0", null));
        sink.flush();

        assertEquals(1, count("instruments"));
    }

    @Test
    void unknownSourceNameFailsLoudAtConstruction() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new PostgresStore(database.dataSource(), "default-user", "not-a-source"));
        assertEquals(true, thrown.getMessage().contains("not-a-source"));
    }
}
