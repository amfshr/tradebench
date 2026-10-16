package dev.amfshr.tradebench.marketdata.store;

import dev.amfshr.tradebench.marketdata.testutil.PostgresTestBase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The archive manifest's contract (D25 R6, E1-T6): one object per market-day per kind; a dump is no market's. */
class ArchivesSchemaTest extends PostgresTestBase {

    private static final String SHA = "a".repeat(64);

    @BeforeEach
    void clean() throws SQLException {
        try (Connection c = database.dedicatedConnection(); Statement s = c.createStatement()) {
            s.execute("TRUNCATE archives RESTART IDENTITY");
            s.execute("INSERT INTO instruments (epic) VALUES ('IX.D.DAX.DAILY.IP') ON CONFLICT DO NOTHING");
        }
    }

    @Test
    void aMarketDayHoldsOneObjectPerKind() throws SQLException {
        insert(day("parquet_bars_day", "bars/2026-10-16.parquet"));
        insert(day("parquet_ticks_day", "ticks/2026-10-16.parquet"));

        SQLException second = assertThrows(SQLException.class,
                () -> insert(day("parquet_bars_day", "bars/2026-10-16-again.parquet")));
        assertEquals("23505", second.getSQLState(), "the same market-day and kind twice is refused");
        assertEquals(2, count());
    }

    @Test
    void theManifestRefusesWhatCouldNotBeAnObject() {
        assertRefused(day("parquet_minutes_day", "x.parquet"), "an unknown kind");
        assertRefused(row("parquet_bars_day", "(SELECT id FROM instruments)", "1", "NULL", "y.parquet", SHA),
                "a market's file with no day");
        assertRefused(row("pg_dump", "(SELECT id FROM instruments)", "NULL", "NULL", "dump.sql.gz", SHA),
                "a dump that names a market");
        assertRefused(row("parquet_bars_day", "(SELECT id FROM instruments)", "1", "DATE '2026-10-16'", "z.parquet",
                "not-a-digest"), "a digest that is not sha256 hex");
    }

    @Test
    void aDumpBelongsToNoMarketOrDay() throws SQLException {
        insert(row("pg_dump", "NULL", "NULL", "NULL", "dumps/2026-10-16.sql.gz", SHA));
        insert(row("pg_dump", "NULL", "NULL", "NULL", "dumps/2026-10-17.sql.gz", SHA));
        assertEquals(2, count(), "dumps are not held to the one-per-market-day rule");
    }

    private static String day(String kind, String key) {
        return row(kind, "(SELECT id FROM instruments)", "1", "DATE '2026-10-16'", key, SHA);
    }

    private static String row(String kind, String instrument, String source, String date, String key, String sha) {
        return "INSERT INTO archives (user_id, source_id, instrument_id, kind, covers_date, object_key, bytes, sha256)"
                + " VALUES (1, " + source + ", " + instrument + ", '" + kind + "', " + date + ", '" + key + "', 10, '"
                + sha + "')";
    }

    private static void insert(String sql) throws SQLException {
        try (Connection c = database.dedicatedConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static void assertRefused(String sql, String what) {
        SQLException refused = assertThrows(SQLException.class, () -> insert(sql), what);
        assertEquals("23514", refused.getSQLState(), what + " — a check constraint, not some other failure");
    }

    private static long count() throws SQLException {
        try (Connection c = database.dedicatedConnection(); Statement s = c.createStatement();
             var r = s.executeQuery("SELECT count(*) FROM archives")) {
            r.next();
            return r.getLong(1);
        }
    }
}
