package dev.amfshr.tradebench.marketdata.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.amfshr.tradebench.core.domain.OhlcPrices;

/** E1-T6 ④: the day-export contract — the Parquet schema, exact prices, UTC microseconds, the source on every row. */
class DayParquetTest {

    @TempDir
    Path dir;

    @Test
    void ticksRoundTripExactlyWithTheirSource() throws Exception {
        Path file = dir.resolve("ticks.parquet");
        DayParquet.writeTicks(file, List.of(
                new ArchivedTick(Instant.parse("2026-10-15T07:00:00.123456Z"), new BigDecimal("24510.5"),
                        new BigDecimal("24511.7"), "ig-stream-demo"),
                new ArchivedTick(Instant.parse("2026-10-15T07:00:01Z"), new BigDecimal("0.000001"),
                        new BigDecimal("24511.123456"), "ig-stream-demo")));

        assertEquals(List.of(
                "ts_utc INT64 TIMESTAMP_MICROS/UTC", "bid INT64 DECIMAL(18,6)", "ask INT64 DECIMAL(18,6)",
                "source BYTE_ARRAY STRING"), parquetSchema(file), "the file's physical schema is the contract");
        List<String> rows = rows(file, "SELECT ts_utc, bid, ask, source FROM read_parquet('%s') ORDER BY ts_utc");
        assertEquals(List.of(
                "2026-10-15T07:00:00.123456Z|24510.500000|24511.700000|ig-stream-demo",
                "2026-10-15T07:00:01Z|0.000001|24511.123456|ig-stream-demo"), rows,
                "UTC microseconds kept; prices exact to six decimals");
    }

    @Test
    void barsRoundTripWithTheirSourceAndAnOptionalVolume() throws Exception {
        Path file = dir.resolve("bars.parquet");
        OhlcPrices bid = new OhlcPrices(new BigDecimal("1.5"), new BigDecimal("2.5"), new BigDecimal("1"), new BigDecimal("2"));
        OhlcPrices ask = new OhlcPrices(new BigDecimal("1.6"), new BigDecimal("2.6"), new BigDecimal("1.1"), new BigDecimal("2.1"));
        DayParquet.writeBars(file, List.of(
                new ArchivedBar(Instant.parse("2026-10-15T07:00:00Z"), bid, ask, 321L, "ig-stream-demo"),
                new ArchivedBar(Instant.parse("2026-10-15T07:01:00Z"), bid, ask, null, "ig-rest-demo")));

        assertEquals(11, parquetSchema(file).size());
        assertEquals("ltv INT64 INT_64", parquetSchema(file).get(9), "volume is an optional 64-bit integer");
        assertEquals(List.of("2026-10-15T07:00:00Z|1.500000|2.100000|321|ig-stream-demo",
                "2026-10-15T07:01:00Z|1.500000|2.100000|null|ig-rest-demo"),
                rows(file, "SELECT start_utc, bid_o, ask_c, ltv, source FROM read_parquet('%s') ORDER BY start_utc"),
                "no volume is absent, not zero; a healed bar says so in the file (P8)");
    }

    @Test
    void aPriceSixDecimalsCannotHoldFailsTheExportRatherThanRound() {
        assertThrows(ArithmeticException.class, () -> DayParquet.writeTicks(dir.resolve("x.parquet"), List.of(
                new ArchivedTick(Instant.parse("2026-10-15T07:00:00Z"), new BigDecimal("1.0000001"), BigDecimal.ONE,
                        "ig-stream-demo"))));
    }

    @Test
    void anEmptyDayIsAValidEmptyFile() throws Exception {
        Path file = dir.resolve("empty.parquet");
        DayParquet.writeTicks(file, List.of());
        assertTrue(Files.size(file) > 0);
        assertTrue(rows(file, "SELECT * FROM read_parquet('%s')").isEmpty());
    }

    /** name, physical type, logical type — read from the file's own metadata. */
    private static List<String> parquetSchema(Path file) throws Exception {
        List<String> columns = new ArrayList<>();
        try (Connection duck = DriverManager.getConnection("jdbc:duckdb:"); Statement s = duck.createStatement();
             ResultSet r = s.executeQuery("SELECT name, type, logical_type, converted_type FROM parquet_schema('"
                     + file + "') WHERE type IS NOT NULL")) {
            while (r.next()) {
                String logical = r.getString(3);
                columns.add(r.getString(1) + " " + r.getString(2) + " " + describe(logical, r.getString(4)));
            }
        }
        return columns;
    }

    private static String describe(String logical, String converted) {
        if (logical == null) {
            return converted == null || converted.equals("UTF8") ? (converted == null ? "-" : "STRING") : converted;
        }
        if (logical.contains("TimestampType")) {
            return "TIMESTAMP_" + (logical.contains("MICROS") ? "MICROS" : "?") + (logical.contains("isAdjustedToUTC=1") ? "/UTC" : "");
        }
        if (logical.contains("DecimalType")) {
            return "DECIMAL(" + logical.replaceAll(".*precision=(\\d+).*", "$1") + "," + logical.replaceAll(".*scale=(\\d+).*", "$1") + ")";
        }
        if (logical.contains("StringType")) {
            return "STRING";
        }
        return logical;
    }

    private static List<String> rows(Path file, String sql) throws Exception {
        List<String> rows = new ArrayList<>();
        try (Connection duck = DriverManager.getConnection("jdbc:duckdb:"); Statement s = duck.createStatement()) {
            s.execute("SET TimeZone = 'UTC'");
            try (ResultSet r = s.executeQuery(String.format(sql, file))) {
                int n = r.getMetaData().getColumnCount();
                while (r.next()) {
                    List<String> cells = new ArrayList<>();
                    for (int i = 1; i <= n; i++) {
                        Object v = r.getObject(i);
                        cells.add(v instanceof OffsetDateTime t ? t.toInstant().toString() : String.valueOf(v));
                    }
                    rows.add(String.join("|", cells));
                }
            }
        }
        return rows;
    }
}
