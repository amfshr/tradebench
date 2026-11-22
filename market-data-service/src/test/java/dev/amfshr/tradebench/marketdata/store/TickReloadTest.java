package dev.amfshr.tradebench.marketdata.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.amfshr.tradebench.core.domain.Tick;
import dev.amfshr.tradebench.marketdata.archive.ArchivedTick;
import dev.amfshr.tradebench.marketdata.archive.DayParquet;
import dev.amfshr.tradebench.marketdata.testutil.PostgresTestBase;

/** E1-T17 (D39): an archived day's ticks put back after a restore — every one, once, and counted back. */
class TickReloadTest extends PostgresTestBase {

    private static final String DAX = "IX.D.DAX.DAILY.IP";
    private static final LocalDate DAY = LocalDate.of(2026, 10, 15);
    private static final Instant MIDNIGHT = Instant.parse("2026-10-15T00:00:00Z");

    @TempDir
    Path dir;
    private PostgresStore stream;

    @BeforeEach
    void clean() throws Exception {
        try (Connection c = database.dedicatedConnection(); Statement s = c.createStatement()) {
            s.execute("TRUNCATE ticks RESTART IDENTITY");
        }
        stream = new PostgresStore(database.dataSource(), "platform", "ig-stream-demo");
    }

    @AfterEach
    void close() {
        stream.close();
    }

    /** The real path end to end: streamed, archived to a file, gone from the database (the dump left them out). */
    private List<ArchivedTick> archivedAndDropped() throws Exception {
        stream.write(new Tick(DAX, Instant.parse("2026-10-15T00:00:00Z"), new BigDecimal("24510.5"), new BigDecimal("24511.7")));
        stream.write(new Tick(DAX, Instant.parse("2026-10-15T07:00:00.123456Z"), new BigDecimal("24512.25"), new BigDecimal("24513")));
        stream.write(new Tick(DAX, Instant.parse("2026-10-15T23:59:59.999999Z"), new BigDecimal("24500"), new BigDecimal("24501.5")));
        stream.write(new Tick(DAX, Instant.parse("2026-10-14T23:59:59.999999Z"), BigDecimal.TEN, BigDecimal.TEN)); // the day before
        stream.write(new Tick(DAX, Instant.parse("2026-10-16T00:00:00Z"), BigDecimal.ONE, BigDecimal.TWO)); // the next day
        stream.flush();
        Path file = dir.resolve("ticks.parquet");
        DayParquet.writeTicks(file, new PostgresArchiveStore(database.dataSource(), "platform", "ig-stream-demo")
                .ticks(DAX, MIDNIGHT, MIDNIGHT.plusSeconds(86_400)));
        try (Connection c = database.dedicatedConnection(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM ticks WHERE ts_utc >= '2026-10-15T00:00:00Z' AND ts_utc < '2026-10-16T00:00:00Z'");
        }
        return DayParquet.readTicks(file); // the neighbouring days' ticks stay: the day's count must reach neither
    }

    private List<String> rows() throws Exception {
        List<String> rows = new ArrayList<>();
        try (Connection c = database.dedicatedConnection(); Statement s = c.createStatement();
                ResultSet r = s.executeQuery("""
                        SELECT u.name, so.name, i.epic, t.ts_utc, t.bid, t.ask FROM ticks t JOIN users u ON u.id = t.user_id
                        JOIN sources so ON so.id = t.source_id JOIN instruments i ON i.id = t.instrument_id ORDER BY t.ts_utc""")) {
            while (r.next()) {
                rows.add(r.getString(1) + " " + r.getString(2) + " " + r.getString(3) + " "
                        + r.getObject(4, java.time.OffsetDateTime.class).toInstant() + " "
                        + r.getBigDecimal(5).stripTrailingZeros().toPlainString() + " "
                        + r.getBigDecimal(6).stripTrailingZeros().toPlainString());
            }
        }
        return rows;
    }

    @Test
    void anArchivedDayComesBackWholeWithItsDimensions() throws Exception {
        TickReload.Day day = new TickReload(database.dataSource(), "platform", DAX).reload(DAY, archivedAndDropped());

        assertEquals(new TickReload.Day(DAY, 3, 3, 3), day);
        assertTrue(day.whole());
        assertEquals(List.of(
                "platform ig-stream-demo IX.D.DAX.DAILY.IP 2026-10-14T23:59:59.999999Z 10 10",
                "platform ig-stream-demo IX.D.DAX.DAILY.IP 2026-10-15T00:00:00Z 24510.5 24511.7",
                "platform ig-stream-demo IX.D.DAX.DAILY.IP 2026-10-15T07:00:00.123456Z 24512.25 24513",
                "platform ig-stream-demo IX.D.DAX.DAILY.IP 2026-10-15T23:59:59.999999Z 24500 24501.5",
                "platform ig-stream-demo IX.D.DAX.DAILY.IP 2026-10-16T00:00:00Z 1 2"), rows(),
                "the day's first and last microsecond back; the days either side untouched and not counted as this day's");
    }

    @Test
    void aSecondReloadChangesNothing() throws Exception {
        List<ArchivedTick> ticks = archivedAndDropped();
        TickReload reload = new TickReload(database.dataSource(), "platform", DAX);
        reload.reload(DAY, ticks);
        List<String> once = rows();

        assertEquals(new TickReload.Day(DAY, 3, 0, 3), reload.reload(DAY, ticks), "every tick already home: none inserted");
        assertEquals(once, rows());
    }

    @Test
    void onlyTheTicksTheDatabaseLacksAreInserted() throws Exception {
        List<ArchivedTick> ticks = archivedAndDropped();
        TickReload reload = new TickReload(database.dataSource(), "platform", DAX);
        reload.reload(DAY, ticks.subList(0, 1)); // a partial earlier attempt

        assertEquals(new TickReload.Day(DAY, 3, 2, 3), reload.reload(DAY, ticks));
    }

    @Test
    void aDayIsWholeExactlyWhenTheDatabaseHoldsEveryArchivedTick() {
        assertTrue(new TickReload.Day(DAY, 3, 0, 3).whole(), "the equality is whole");
        assertTrue(new TickReload.Day(DAY, 3, 0, 4).whole(), "a tick the archive lacks is not a loss of this reload");
        assertFalse(new TickReload.Day(DAY, 3, 3, 2).whole(), "one fewer is short");
    }
}
