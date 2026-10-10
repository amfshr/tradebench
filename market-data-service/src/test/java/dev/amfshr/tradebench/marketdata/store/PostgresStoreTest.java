package dev.amfshr.tradebench.marketdata.store;

import dev.amfshr.tradebench.marketdata.testutil.PostgresTestBase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.io.PrintWriter;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTransientConnectionException;
import java.util.logging.Logger;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Statement;
import java.time.Duration;
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

    // --- E1-T9: hold-and-retry — the store side ---------------------------------------------

    /** Kill every backend but ours — the store's connection and the pool's idle ones — then make
     * the pool forget them. In production Hikari re-validates a connection idle for more than
     * 500ms before handing it out, and the pump's backoff guarantees that idleness; back-to-back
     * test borrows would land inside the bypass window and receive a dead connection. */
    private void killOtherBackends() throws SQLException {
        try (Connection c = database.dataSource().getConnection();
                Statement s = c.createStatement()) {
            s.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity"
                    + " WHERE pid <> pg_backend_pid() AND datname = current_database()");
        }
        ((HikariDataSource) database.dataSource()).getHikariPoolMXBean().softEvictConnections();
    }

    @Test
    void aKilledConnectionIsRetryableAndRecoverLandsEveryHeldTick() throws SQLException {
        sink.write(tick(DAX, "2026-09-28T07:00:01Z", "1.1", "1.2"));
        sink.write(tick(DAX, "2026-09-28T07:00:02Z", "1.3", "1.4"));
        sink.write(tick(DAX, "2026-09-28T07:00:03Z", "1.5", "1.6"));
        killOtherBackends();

        PersistenceException thrown = assertThrows(PersistenceException.class, sink::flush);
        assertTrue(thrown.retryable(), "a terminated backend is weather: " + thrown.getCause());

        sink.recover();
        sink.flush();

        assertEquals(3, count("ticks"), "the batch held across the outage lands whole");
    }

    @Test
    void aBarWriteOnAKilledConnectionIsRetryableAndTheBarLandsAfterRecover() throws SQLException {
        sink.write(bar("2026-09-28T07:00:00Z", "24512.0", 10L)); // warms the instrument cache
        killOtherBackends();

        PersistenceException thrown = assertThrows(PersistenceException.class,
                () -> sink.write(bar("2026-09-28T07:01:00Z", "24513.0", 11L)));
        assertTrue(thrown.retryable());

        sink.recover();
        sink.write(bar("2026-09-28T07:01:00Z", "24513.0", 11L)); // the pump re-drains the same bar

        assertEquals(2, count("bars_1m"));
    }

    @Test
    void aTickHeldBeforeItsBindFailsIsStillRecovered() throws SQLException {
        killOtherBackends(); // nothing cached: the first write must look DAX up on a dead connection

        PersistenceException thrown = assertThrows(PersistenceException.class,
                () -> sink.write(tick(DAX, "2026-09-28T07:00:01Z", "1.1", "1.2")));
        assertTrue(thrown.retryable());

        sink.recover();
        sink.flush();

        assertEquals(1, count("ticks"), "held before anything could fail — never lost to the bind");
    }

    @Test
    void recoverOnAHealthyStoreIsHarmlessAndCarriesTheBatch() throws SQLException {
        sink.write(tick(DAX, "2026-09-28T07:00:01Z", "1.1", "1.2"));

        sink.recover();
        sink.flush();
        sink.write(bar("2026-09-28T07:00:00Z", "24512.0", 10L));

        assertEquals(1, count("ticks"));
        assertEquals(1, count("bars_1m"));
    }

    @Test
    void recoverWhileTheDatabaseIsStillDownThrowsRetryableAndKeepsTheBatch() throws SQLException {
        FlakyDataSource flaky = new FlakyDataSource(database.dataSource());
        PostgresStore store = new PostgresStore(flaky, "default-user", "ig-stream-demo");
        store.write(tick(DAX, "2026-09-28T07:00:01Z", "1.1", "1.2"));
        store.write(tick(DAX, "2026-09-28T07:00:02Z", "1.3", "1.4"));
        killOtherBackends();
        flaky.down = true; // the pool cannot hand out a connection — Hikari's timeout

        assertThrows(PersistenceException.class, store::flush);
        PersistenceException stillDown = assertThrows(PersistenceException.class, store::recover);
        assertTrue(stillDown.retryable(), "still weather — the pump keeps backing off");

        flaky.down = false;
        store.recover();
        store.flush();
        assertEquals(2, count("ticks"), "two recovery attempts, nothing dropped between them");
        store.close();
        sink.recover(); // the setUp store's connection was killed too — leave it closable
    }

    @Test
    void aBrokenStoreRefusesWritesAndFlushesUntilRecovered() throws SQLException {
        sink.write(tick(DAX, "2026-09-28T07:00:01Z", "1.1", "1.2"));
        sink.write(tick(DAX, "2026-09-28T07:00:02Z", "1.3", "1.4"));
        sink.write(tick(DAX, "2026-09-28T07:00:03Z", "1.5", "1.6"));
        killOtherBackends();
        assertThrows(PersistenceException.class, sink::flush);

        // pgjdbc drops its batch before executing: a second flush on the same statement would send
        // nothing and acknowledge everything — the store must refuse rather than lie.
        PersistenceException refused = assertThrows(PersistenceException.class, sink::flush);
        assertTrue(refused.retryable(), "the refusal carries the original failure's classification");
        assertThrows(PersistenceException.class,
                () -> sink.write(bar("2026-09-28T07:00:00Z", "24512.0", 10L)));
        assertEquals(0, count("ticks"), "nothing acknowledged, nothing landed");

        sink.recover();
        sink.flush();
        assertEquals(3, count("ticks"), "acknowledged only once it landed");
    }

    /** A pool whose {@code getConnection} can be made to time out, as Hikari's does while Postgres
     * is down (the real pool is behind it, so everything else is real). */
    private static final class FlakyDataSource implements DataSource {
        private final DataSource real;
        volatile boolean down;

        FlakyDataSource(DataSource real) {
            this.real = real;
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (down) {
                throw new SQLTransientConnectionException(
                        "pool - Connection is not available, request timed out", "08001");
            }
            return real.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return real.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            real.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            real.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return real.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return real.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return real.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return real.isWrapperFor(iface);
        }
    }

    @Test
    void recoverAgainstADatabaseThatRefusesWritesFailsRetryablyAndKeepsTheBatch() throws SQLException {
        // E1-T10 #6: disk full answers connections and refuses writes (SQLSTATE 53100). A recovery
        // that only reconnected would "succeed" and the next flush would start a fresh episode at
        // attempt one, every five seconds, forever. Recovery lands the held batch, so it fails here.
        execute("CREATE FUNCTION refuse_writes() RETURNS trigger AS $$ BEGIN"
                + " RAISE EXCEPTION 'disk full (simulated)' USING ERRCODE = '53100'; END $$ LANGUAGE plpgsql");
        execute("CREATE TRIGGER refuse_ticks BEFORE INSERT ON ticks FOR EACH ROW EXECUTE FUNCTION refuse_writes()");
        try {
            sink.write(tick(DAX, "2026-09-28T09:00:00.001Z", "24510.5", "24511.5"));
            PersistenceException refused = assertThrows(PersistenceException.class, sink::flush);
            assertTrue(refused.retryable(), "53100 is weather by the taxonomy");
            PersistenceException stillRefused = assertThrows(PersistenceException.class, sink::recover,
                    "the held batch is the round trip: it did not land, so nothing recovered");
            assertTrue(stillRefused.retryable());
            assertEquals(0, count("ticks"));
            assertThrows(PersistenceException.class, () -> sink.write(bar("2026-09-28T09:00:00Z", "24512.0", 7L)),
                    "still broken until a recovery lands");
        } finally {
            execute("DROP TRIGGER refuse_ticks ON ticks");
            execute("DROP FUNCTION refuse_writes()");
        }
        sink.recover();
        assertEquals(1, count("ticks"), "the disk came back: recovery landed the held tick");
    }

    @Test
    void aPausedDatabaseSurfacesAsARetryableFailureWithinTheSocketTimeout() throws Exception {
        // E1-T10 #7 (D29 (2)): a half-open connection — the server frozen without a RST — must
        // become a PersistenceException the hold can see, not a thread parked in a socket read.
        try (Database quick = Database.connect(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword(), Duration.ofSeconds(2))) {
            PostgresStore frozen = new PostgresStore(quick.dataSource(), "default-user", "ig-stream-demo");
            String containerId = postgres.getContainerId();
            postgres.getDockerClient().pauseContainerCmd(containerId).exec();
            try {
                long started = System.nanoTime();
                PersistenceException timedOut = assertTimeoutPreemptively(Duration.ofSeconds(15),
                        () -> assertThrows(PersistenceException.class,
                                () -> frozen.write(bar("2026-09-28T09:01:00Z", "24512.0", 7L))));
                assertTrue(timedOut.retryable(), "a socket timeout is weather: " + timedOut.getCause());
                assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(10)) < 0,
                        "surfaced within the timeout, not a silent hang");
            } finally {
                postgres.getDockerClient().unpauseContainerCmd(containerId).exec();
            }
            frozen.recover();
            frozen.write(bar("2026-09-28T09:01:00Z", "24512.0", 7L));
            assertEquals(1, count("bars_1m"), "the server is back: a fresh connection lands the bar");
            frozen.close();
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection c = database.dataSource().getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
