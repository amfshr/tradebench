package dev.amfshr.tradebench.marketdata.store;

import dev.amfshr.tradebench.marketdata.testutil.PostgresTestBase;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.core.domain.Bar1m;
import dev.amfshr.tradebench.core.domain.OhlcPrices;
import dev.amfshr.tradebench.core.domain.Tick;
import dev.amfshr.tradebench.marketdata.archive.ArchivedBar;

/** E1-T6 ④: the archive reads the day's streamed ticks and every source's bars, and keeps one manifest row per object. */
class PostgresArchiveStoreTest extends PostgresTestBase {

    private static final String DAX = "IX.D.DAX.DAILY.IP";
    private static final Instant DAY = Instant.parse("2026-10-15T00:00:00Z");
    private PostgresStore stream;
    private PostgresArchiveStore archive;

    @BeforeEach
    void clean() throws Exception {
        try (Connection c = database.dedicatedConnection(); Statement s = c.createStatement()) {
            s.execute("TRUNCATE ticks, bars_1m, archives RESTART IDENTITY");
        }
        stream = new PostgresStore(database.dataSource(), "platform", "ig-stream-demo");
        archive = new PostgresArchiveStore(database.dataSource(), "platform", "ig-stream-demo");
    }

    @AfterEach
    void close() {
        stream.close();
    }

    @Test
    void theDaysTicksAreItsOwnAndInOrder() throws Exception {
        stream.write(new Tick(DAX, Instant.parse("2026-10-15T07:00:02Z"), BigDecimal.ONE, BigDecimal.TWO));
        stream.write(new Tick(DAX, Instant.parse("2026-10-15T07:00:01Z"), BigDecimal.ONE, BigDecimal.TWO));
        stream.write(new Tick(DAX, Instant.parse("2026-10-16T00:00:00Z"), BigDecimal.ONE, BigDecimal.TWO)); // the next day
        stream.flush();

        assertEquals(List.of(Instant.parse("2026-10-15T07:00:01Z"), Instant.parse("2026-10-15T07:00:02Z")),
                archive.ticks(DAX, DAY, DAY.plusSeconds(86_400)).stream().map(t -> t.tsUtc()).toList(),
                "midnight belongs to the next day; oldest first");
    }

    @Test
    void theDaysBarsComeFromEverySourceAndSayWhich() {
        stream.write(bar("2026-10-15T07:00:00Z"));
        PostgresStore healed = new PostgresStore(database.dataSource(), "platform", "ig-rest-demo");
        healed.write(bar("2026-10-15T07:01:00Z"));
        healed.close();

        List<ArchivedBar> bars = archive.bars(DAX, DAY, DAY.plusSeconds(86_400));

        assertEquals(List.of("ig-stream-demo", "ig-rest-demo"), bars.stream().map(ArchivedBar::source).toList());
    }

    @Test
    void aReRunReplacesItsManifestRowRatherThanAddingOne() throws Exception {
        stream.write(bar("2026-10-15T07:00:00Z")); // registers the instrument
        LocalDate d = LocalDate.of(2026, 10, 15);
        archive.record(DAX, d, "parquet_bars_day", "platform/ig-stream-demo/x/2026-10-15/bars.parquet", 10, "a".repeat(64));
        archive.record(DAX, d, "parquet_bars_day", "platform/ig-stream-demo/x/2026-10-15/bars.parquet", 12, "b".repeat(64));

        try (Connection c = database.dedicatedConnection(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT count(*), max(bytes), max(sha256) FROM archives")) {
            r.next();
            assertEquals(1, r.getInt(1), "one object per market-day per kind");
            assertEquals(12, r.getLong(2));
            assertEquals("b".repeat(64), r.getString(3));
        }
    }

    private static Bar1m bar(String iso) {
        OhlcPrices p = new OhlcPrices(BigDecimal.ONE, BigDecimal.TWO, BigDecimal.ONE, BigDecimal.TWO);
        return new Bar1m(DAX, Instant.parse(iso), p, p, null);
    }
}
