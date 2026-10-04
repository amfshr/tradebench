package dev.amfshr.tradebench.marketdata.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.jspecify.annotations.Nullable;

import dev.amfshr.tradebench.core.domain.Bar1m;
import dev.amfshr.tradebench.core.domain.OhlcPrices;
import dev.amfshr.tradebench.core.domain.Tick;

/**
 * The real write path (T4): bars apply immediately (ack-after-apply — the pump removes a bar
 * from its queue only after this write returns); ticks batch and land on {@link #flush()} or
 * when the batch fills. The pending batch is <b>held here</b>, not only in the driver: a
 * {@link PersistenceException#retryable() retryable} failure leaves it intact for
 * {@link #recover()}, which re-acquires a pooled connection, re-prepares, and re-binds every held
 * tick (E1-T9 hold-and-retry; the dedupe constraint makes a re-sent batch idempotent). After a
 * failure every write and flush is <b>refused</b> until {@code recover()}: the driver drops its
 * batch on a failed {@code executeBatch}, so a retry on the same statement would send nothing and
 * acknowledge everything. Market data only (decision #1)
 * — service events and bar gaps go to the observability store, never here. Single-threaded by
 * contract: only the pump calls a sink.
 */
public final class PostgresStore implements CaptureStore {

    static final int TICK_BATCH_LIMIT = 500;

    private static final String INSERT_TICK = """
            INSERT INTO ticks (user_id, source_id, instrument_id, ts_utc, bid, ask)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT ON CONSTRAINT ticks_dedupe DO NOTHING""";
    private static final String UPSERT_BAR = """
            INSERT INTO bars_1m (user_id, source_id, instrument_id, start_utc,
                bid_o, bid_h, bid_l, bid_c, ask_o, ask_h, ask_l, ask_c, ltv)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (user_id, source_id, instrument_id, start_utc) DO UPDATE SET
                bid_o = EXCLUDED.bid_o, bid_h = EXCLUDED.bid_h,
                bid_l = EXCLUDED.bid_l, bid_c = EXCLUDED.bid_c,
                ask_o = EXCLUDED.ask_o, ask_h = EXCLUDED.ask_h,
                ask_l = EXCLUDED.ask_l, ask_c = EXCLUDED.ask_c,
                ltv = EXCLUDED.ltv""";

    private final DataSource dataSource;
    private final short userId;
    private final short sourceId;
    private final Map<String, Short> instrumentIds = new HashMap<>();
    private final List<Tick> pending = new ArrayList<>(); // the batch, owned here
    private Connection connection;
    private PreparedStatement tickInsert;
    private PreparedStatement barUpsert;
    private  SQLException brokenBy; // the failure that broke the sink — set until recover()

    public PostgresStore(DataSource dataSource, String userName, String sourceName) {
        this.dataSource = dataSource;
        try {
            this.connection = dataSource.getConnection();
            this.userId = lookupId(connection, "SELECT id FROM users WHERE name = ?", userName,
                    "user");
            this.sourceId = lookupId(connection, "SELECT id FROM sources WHERE name = ?",
                    sourceName, "source");
            this.tickInsert = connection.prepareStatement(INSERT_TICK);
            this.barUpsert = connection.prepareStatement(UPSERT_BAR);
        } catch (SQLException e) {
            throw new PersistenceException("PostgresStore initialisation failed", e);
        }
    }

    @Override
    public void write(Tick tick) {
        pending.add(tick); // held before anything can fail — recover() re-binds from here
        refuseIfBroken();
        try {
            bind(tickInsert, connection, tick);
            tickInsert.addBatch();
            if (pending.size() >= TICK_BATCH_LIMIT) {
                flush();
            }
        } catch (SQLException e) {
            brokenBy = e;
            throw new PersistenceException("tick write failed", e);
        }
    }

    @Override
    public void write(Bar1m bar) {
        refuseIfBroken();
        try {
            barUpsert.setShort(1, userId);
            barUpsert.setShort(2, sourceId);
            barUpsert.setShort(3, instrumentId(connection, bar.epic()));
            barUpsert.setObject(4, utc(bar.startUtc()));
            setOhlc(barUpsert, 5, bar.bid());
            setOhlc(barUpsert, 9, bar.ask());
            if (bar.lastTradedVolume() != null) {
                barUpsert.setLong(13, bar.lastTradedVolume());
            } else {
                barUpsert.setNull(13, java.sql.Types.BIGINT);
            }
            barUpsert.executeUpdate();
        } catch (SQLException e) {
            brokenBy = e;
            throw new PersistenceException("bar write failed", e);
        }
    }

    @Override
    public void flush() {
        if (pending.isEmpty()) {
            return;
        }
        refuseIfBroken();
        try {
            tickInsert.executeBatch();
            pending.clear(); // acknowledged only once the batch has landed
        } catch (SQLException e) {
            brokenBy = e; // the driver has already dropped its batch — only recover() can re-send it
            throw new PersistenceException("tick batch flush failed", e);
        }
    }

    @Override
    public void recover() {
        @Nullable Connection fresh = null;
        @Nullable PreparedStatement freshTicks = null;
        @Nullable PreparedStatement freshBars = null;
        try {
            fresh = dataSource.getConnection();
            freshTicks = fresh.prepareStatement(INSERT_TICK);
            freshBars = fresh.prepareStatement(UPSERT_BAR);
            for (Tick held : pending) {
                bind(freshTicks, fresh, held);
                freshTicks.addBatch();
            }
        } catch (SQLException e) {
            closeQuietly(freshTicks, freshBars, fresh);
            throw new PersistenceException("sink recovery failed — still unavailable", e);
        }
        // Only once the fresh set is whole do the suspect ones go: a failed recovery changes nothing.
        closeQuietly(tickInsert, barUpsert, connection);
        connection = fresh;
        tickInsert = freshTicks;
        barUpsert = freshBars;
        brokenBy = null;
    }

    private void refuseIfBroken() {
        if (brokenBy != null) {
            throw new PersistenceException("sink is broken since an earlier failure — recover() first",
                    brokenBy);
        }
    }

    @Override
    public void close() {
        flush();
        try {
            tickInsert.close();
            barUpsert.close();
            connection.close();
        } catch (SQLException e) {
            throw new PersistenceException("PostgresStore close failed", e);
        }
    }

    private void bind(PreparedStatement insert, Connection on, Tick tick) throws SQLException {
        insert.setShort(1, userId);
        insert.setShort(2, sourceId);
        insert.setShort(3, instrumentId(on, tick.epic()));
        insert.setObject(4, utc(tick.timestamp()));
        insert.setBigDecimal(5, tick.bid());
        insert.setBigDecimal(6, tick.ask());
    }

    private short instrumentId(Connection on, String epic) throws SQLException {
        Short cached = instrumentIds.get(epic);
        if (cached != null) {
            return cached;
        }
        try (PreparedStatement insert = on.prepareStatement(
                "INSERT INTO instruments (epic) VALUES (?) ON CONFLICT (epic) DO NOTHING")) {
            insert.setString(1, epic);
            insert.executeUpdate();
        }
        short id = lookupId(on, "SELECT id FROM instruments WHERE epic = ?", epic, "instrument");
        instrumentIds.put(epic, id);
        return id;
    }

    // Throws the SQLException through, so every SQL failure reaches a write's catch and breaks
    // the sink; only the constructor wraps it.
    private static short lookupId(Connection on, String sql, String name, String kind)
            throws SQLException {
        try (PreparedStatement statement = on.prepareStatement(sql)) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException(
                            "unknown " + kind + " '" + name + "' — schema seeds are the"
                                    + " source of truth; refusing to invent one");
                }
                return result.getShort(1);
            }
        }
    }

    // Discarding dead or superseded JDBC objects: a failure to close them has nothing to act on.
    private static void closeQuietly(@Nullable AutoCloseable... closeables) {
        for (AutoCloseable closeable : closeables) {
            if (closeable != null) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    // already dead
                }
            }
        }
    }

    private static void setOhlc(PreparedStatement statement, int firstIndex, OhlcPrices prices)
            throws SQLException {
        statement.setBigDecimal(firstIndex, prices.open());
        statement.setBigDecimal(firstIndex + 1, prices.high());
        statement.setBigDecimal(firstIndex + 2, prices.low());
        statement.setBigDecimal(firstIndex + 3, prices.close());
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
