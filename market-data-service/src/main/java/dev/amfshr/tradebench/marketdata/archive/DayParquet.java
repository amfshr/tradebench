package dev.amfshr.tradebench.marketdata.archive;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * The day-export contract (E1-T6): the two files a market-day becomes, written through an in-memory
 * DuckDB (nod ruling 3's fallback — parquet-java needs Hadoop at runtime). Timestamps are UTC
 * ({@code TIMESTAMP WITH TIME ZONE}, microseconds); prices are {@code DECIMAL(18,6)}, exact — a
 * price needing more than six decimals fails the export rather than be rounded; every row carries
 * its source (P8). Zstandard-compressed.
 */
public final class DayParquet {

    static final int SCALE = 6;

    static final String TICKS_TABLE = """
            CREATE TABLE ticks (ts_utc TIMESTAMPTZ NOT NULL, bid DECIMAL(18,6) NOT NULL,
                ask DECIMAL(18,6) NOT NULL, source VARCHAR NOT NULL)""";

    static final String BARS_TABLE = """
            CREATE TABLE bars (start_utc TIMESTAMPTZ NOT NULL,
                bid_o DECIMAL(18,6) NOT NULL, bid_h DECIMAL(18,6) NOT NULL,
                bid_l DECIMAL(18,6) NOT NULL, bid_c DECIMAL(18,6) NOT NULL,
                ask_o DECIMAL(18,6) NOT NULL, ask_h DECIMAL(18,6) NOT NULL,
                ask_l DECIMAL(18,6) NOT NULL, ask_c DECIMAL(18,6) NOT NULL,
                ltv BIGINT, source VARCHAR NOT NULL)""";

    private DayParquet() {
    }

    public static void writeTicks(Path file, List<ArchivedTick> ticks) throws IOException {
        export(file, TICKS_TABLE, "ticks", "INSERT INTO ticks VALUES (?, ?, ?, ?)", ticks, (s, t) -> {
            s.setObject(1, utc(t.tsUtc()));
            s.setBigDecimal(2, exact(t.bid()));
            s.setBigDecimal(3, exact(t.ask()));
            s.setString(4, t.source());
        });
    }

    public static void writeBars(Path file, List<ArchivedBar> bars) throws IOException {
        export(file, BARS_TABLE, "bars", "INSERT INTO bars VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", bars, (s, b) -> {
            s.setObject(1, utc(b.startUtc()));
            s.setBigDecimal(2, exact(b.bid().open()));
            s.setBigDecimal(3, exact(b.bid().high()));
            s.setBigDecimal(4, exact(b.bid().low()));
            s.setBigDecimal(5, exact(b.bid().close()));
            s.setBigDecimal(6, exact(b.ask().open()));
            s.setBigDecimal(7, exact(b.ask().high()));
            s.setBigDecimal(8, exact(b.ask().low()));
            s.setBigDecimal(9, exact(b.ask().close()));
            if (b.lastTradedVolume() == null) {
                s.setNull(10, Types.BIGINT);
            } else {
                s.setLong(10, b.lastTradedVolume());
            }
            s.setString(11, b.source());
        });
    }

    @FunctionalInterface
    private interface Binder<T> {
        void bind(PreparedStatement statement, T row) throws SQLException;
    }

    private static <T> void export(Path file, String create, String table, String insert, List<T> rows,
            Binder<T> binder) throws IOException {
        try (Connection duck = DriverManager.getConnection("jdbc:duckdb:")) {
            try (Statement s = duck.createStatement()) {
                s.execute("SET TimeZone = 'UTC'");
                s.execute(create);
            }
            duck.setAutoCommit(false);
            try (PreparedStatement s = duck.prepareStatement(insert)) {
                for (T row : rows) {
                    binder.bind(s, row);
                    s.addBatch();
                }
                s.executeBatch();
            }
            duck.commit();
            try (Statement s = duck.createStatement()) {
                s.execute("COPY " + table + " TO '" + file.toAbsolutePath().toString().replace("'", "''")
                        + "' (FORMAT PARQUET, COMPRESSION ZSTD)");
            }
        } catch (SQLException e) {
            throw new IOException("parquet export of " + table + " failed", e);
        }
    }

    private static OffsetDateTime utc(Instant t) {
        return t.atOffset(ZoneOffset.UTC);
    }

    /** Exact or refused: a price needing more than six decimals is never silently rounded. */
    static BigDecimal exact(BigDecimal price) {
        return price.setScale(SCALE, RoundingMode.UNNECESSARY);
    }
}
