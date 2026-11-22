package dev.amfshr.tradebench.marketdata.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.amfshr.tradebench.marketdata.archive.ArchivedTick;
import dev.amfshr.tradebench.marketdata.archive.DayParquet;
import dev.amfshr.tradebench.marketdata.testutil.PostgresTestBase;

/** E1-T17 (D39): reload-ticks walks the archive's own layout, day by day, and says plainly when a day is short. */
class ReloadTicksTest extends PostgresTestBase {

    private static final String DAX = "IX.D.DAX.DAILY.IP";

    @TempDir
    Path root;
    private final List<String> out = new ArrayList<>();

    @BeforeEach
    void clean() throws Exception {
        try (Connection c = database.dedicatedConnection(); Statement s = c.createStatement()) {
            s.execute("TRUNCATE ticks RESTART IDENTITY");
        }
    }

    /** A day's file where the archiver puts it: user / source / market / day. */
    private void archived(String day, ArchivedTick... ticks) throws Exception {
        Path dir = Files.createDirectories(root.resolve("platform/ig-stream-demo").resolve(DAX).resolve(day));
        DayParquet.writeTicks(dir.resolve("ticks.parquet"), List.of(ticks));
    }

    private static ArchivedTick tick(String at) {
        return new ArchivedTick(Instant.parse(at), new BigDecimal("24510.5"), new BigDecimal("24511.7"), "ig-stream-demo");
    }

    private boolean run(String first, String last) throws Exception {
        return ReloadTicks.run(root, "platform", DAX, LocalDate.parse(first), LocalDate.parse(last), database.dataSource(), out::add);
    }

    @Test
    void everyArchivedDayInTheRangeIncludingTheLastComesBack() throws Exception {
        archived("2026-10-14", tick("2026-10-14T09:00:00Z"));
        archived("2026-10-15", tick("2026-10-15T09:00:00Z"), tick("2026-10-15T09:00:01Z"));
        archived("2026-10-16", tick("2026-10-16T09:00:00Z"));

        assertTrue(run("2026-10-15", "2026-10-16"), String.join("\n", out));
        assertEquals(List.of(
                "reload-ticks: IX.D.DAX.DAILY.IP 2026-10-15 (ig-stream-demo) — 2 archived, 2 inserted, 2 in the database",
                "reload-ticks: IX.D.DAX.DAILY.IP 2026-10-16 (ig-stream-demo) — 1 archived, 1 inserted, 1 in the database",
                "reload-ticks: 2 days, every one whole"), out, "the 14th is outside the range");
    }

    @Test
    void aShortDayFailsTheReloadNamingTheDay() throws Exception {
        // a file the database cannot hold whole: the same tick twice, which the dedupe key keeps once
        archived("2026-10-15", tick("2026-10-15T09:00:00Z"), tick("2026-10-15T09:00:00Z"));
        archived("2026-10-16", tick("2026-10-16T09:00:00Z"));

        assertFalse(run("2026-10-15", "2026-10-16"));
        assertEquals(List.of(
                "ERROR reload-ticks: IX.D.DAX.DAILY.IP 2026-10-15 (ig-stream-demo) — 2 archived, 1 inserted, 1 in the database"
                        + " — SHORT: archived ticks are missing",
                "reload-ticks: IX.D.DAX.DAILY.IP 2026-10-16 (ig-stream-demo) — 1 archived, 1 inserted, 1 in the database",
                "ERROR reload-ticks: 2 days, some SHORT or refused"), out, "a later whole day does not hide an earlier short one");
    }

    @Test
    void aDayWithTwoSourcesIsOneDayWithALineForEachSource() throws Exception {
        archived("2026-10-15", tick("2026-10-15T09:00:00Z"));
        Path live = Files.createDirectories(root.resolve("platform/ig-stream-live").resolve(DAX).resolve("2026-10-15"));
        DayParquet.writeTicks(live.resolve("ticks.parquet"), List.of(new ArchivedTick(Instant.parse("2026-10-15T09:00:00Z"),
                new BigDecimal("24510.5"), new BigDecimal("24511.7"), "ig-stream-live")));

        assertTrue(run("2026-10-15", "2026-10-15"), String.join("\n", out));
        assertEquals(List.of(
                "reload-ticks: IX.D.DAX.DAILY.IP 2026-10-15 (ig-stream-demo) — 1 archived, 1 inserted, 1 in the database",
                "reload-ticks: IX.D.DAX.DAILY.IP 2026-10-15 (ig-stream-live) — 1 archived, 1 inserted, 1 in the database",
                "reload-ticks: 1 days, every one whole"), out, "each source's ticks counted as its own; one day, not two files");
    }

    @Test
    void aRefusedDayIsNamedAndWritesNothingAndTheOthersStillComeBack() throws Exception {
        // a source the database does not know: refused, never invented (TickReload's lookup) — and the day's batch
        // already sent is rolled back with it, so the refusal leaves nothing half-written
        List<ArchivedTick> ticks = new ArrayList<>();
        for (int i = 0; i < 6_000; i++) {
            ticks.add(tick(Instant.parse("2026-10-15T09:00:00Z").plusMillis(i).toString()));
        }
        ticks.add(new ArchivedTick(Instant.parse("2026-10-15T10:00:00Z"), BigDecimal.ONE, BigDecimal.TWO, "no-such-source"));
        archived("2026-10-15", ticks.toArray(ArchivedTick[]::new));
        archived("2026-10-16", tick("2026-10-16T09:00:00Z"));

        assertFalse(run("2026-10-15", "2026-10-16"));
        assertTrue(out.get(0).startsWith("ERROR reload-ticks: IX.D.DAX.DAILY.IP 2026-10-15 (ig-stream-demo) — refused, nothing written: "),
                out.get(0));
        assertEquals("reload-ticks: IX.D.DAX.DAILY.IP 2026-10-16 (ig-stream-demo) — 1 archived, 1 inserted, 1 in the database",
                out.get(1));
        assertEquals("ERROR reload-ticks: 2 days, some SHORT or refused", out.get(2));
        try (Connection c = database.dedicatedConnection(); Statement s = c.createStatement();
                java.sql.ResultSet r = s.executeQuery("SELECT count(*) FROM ticks WHERE ts_utc < '2026-10-16'")) {
            r.next();
            assertEquals(0, r.getLong(1), "the refused day's first 5,000-tick batch went back with it");
        }
    }

    @Test
    void anUnknownUserIsRefusedForEveryDay() throws Exception {
        archived("2026-10-15", tick("2026-10-15T09:00:00Z"));
        Path elsewhere = Files.createDirectories(root.resolve("nobody/ig-stream-demo").resolve(DAX).resolve("2026-10-15"));
        Files.copy(root.resolve("platform/ig-stream-demo").resolve(DAX).resolve("2026-10-15/ticks.parquet"),
                elsewhere.resolve("ticks.parquet"));

        assertFalse(ReloadTicks.run(root, "nobody", DAX, LocalDate.parse("2026-10-15"), LocalDate.parse("2026-10-15"),
                database.dataSource(), out::add));
        assertTrue(out.get(0).startsWith("ERROR reload-ticks: IX.D.DAX.DAILY.IP 2026-10-15 (ig-stream-demo) — refused"), out.get(0));
    }

    @Test
    void aRangeWithNoArchivedDayFailsRatherThanReportNothing() throws Exception {
        archived("2026-10-15", tick("2026-10-15T09:00:00Z"));

        assertFalse(run("2026-11-01", "2026-11-02"));
        assertEquals(1, out.size());
        assertTrue(out.get(0).startsWith("ERROR reload-ticks: no archived ticks for IX.D.DAX.DAILY.IP from 2026-11-01 to 2026-11-02"), out.get(0));
    }
}
