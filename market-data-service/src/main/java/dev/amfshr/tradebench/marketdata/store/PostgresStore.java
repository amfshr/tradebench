package dev.amfshr.tradebench.marketdata.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.amfshr.tradebench.core.domain.Bar1m;
import dev.amfshr.tradebench.core.domain.OhlcPrices;
import dev.amfshr.tradebench.core.domain.Tick;
import dev.amfshr.tradebench.marketdata.events.EventType;
import dev.amfshr.tradebench.marketdata.ingest.Buffers;
import dev.amfshr.tradebench.marketdata.store.CaptureStore;

/**
 * The real write path (T4): bars and state changes apply immediately (ack-after-apply —
 * the pump removes a bar from its queue only after this write returns); ticks batch and
 * land on {@link #flush()} or when the batch fills (best-effort by design, like their
 * queue). Single-threaded by contract: only the pump calls a sink.
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
    private static final String INSERT_EVENT = """
            INSERT INTO service_events (instance, user_id, source_id, instrument_id, category,
                event_type, severity, event_time_utc, detail)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)""";

    private final Connection connection;
    private final PreparedStatement tickInsert;
    private final PreparedStatement barUpsert;
    private final PreparedStatement eventInsert;
    private final short userId;
    private final short sourceId;
    private final String instance;
    private final Map<String, Short> instrumentIds = new HashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private int pendingTicks;

    public PostgresStore(DataSource dataSource, String userName, String sourceName, String instance) {
        this.instance = instance;
        try {
            this.connection = dataSource.getConnection();
            this.userId = lookupId("SELECT id FROM users WHERE name = ?", userName, "user");
            this.sourceId = lookupId("SELECT id FROM sources WHERE name = ?", sourceName,
                    "source");
            this.tickInsert = connection.prepareStatement(INSERT_TICK);
            this.barUpsert = connection.prepareStatement(UPSERT_BAR);
            this.eventInsert = connection.prepareStatement(INSERT_EVENT);
        } catch (SQLException e) {
            throw new PersistenceException("PostgresStore initialisation failed", e);
        }
    }

    @Override
    public void write(Tick tick) {
        try {
            tickInsert.setShort(1, userId);
            tickInsert.setShort(2, sourceId);
            tickInsert.setShort(3, instrumentId(tick.epic()));
            tickInsert.setObject(4, utc(tick.timestamp()));
            tickInsert.setBigDecimal(5, tick.bid());
            tickInsert.setBigDecimal(6, tick.ask());
            tickInsert.addBatch();
            if (++pendingTicks >= TICK_BATCH_LIMIT) {
                flush();
            }
        } catch (SQLException e) {
            throw new PersistenceException("tick write failed", e);
        }
    }

    @Override
    public void write(Bar1m bar) {
        try {
            barUpsert.setShort(1, userId);
            barUpsert.setShort(2, sourceId);
            barUpsert.setShort(3, instrumentId(bar.epic()));
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
            throw new PersistenceException("bar write failed", e);
        }
    }

    @Override
    public void write(Buffers.StateChange stateChange) {
        // A DLG_FLAG transition is a market_state_change event (v2, D25). Slice C unifies this
        // onto the EventLog seam when it rewires Main; here it rides the pump's existing path.
        try {
            eventInsert.setString(1, instance);
            eventInsert.setShort(2, userId);
            eventInsert.setShort(3, sourceId);
            eventInsert.setShort(4, instrumentId(stateChange.epic()));
            eventInsert.setString(5, EventType.MARKET_STATE_CHANGE.category().db());
            eventInsert.setString(6, EventType.MARKET_STATE_CHANGE.db());
            eventInsert.setString(7, EventType.MARKET_STATE_CHANGE.defaultSeverity().db());
            eventInsert.setObject(8, utc(stateChange.atUtc()));
            eventInsert.setString(9, mapper.createObjectNode()
                    .put("epic", stateChange.epic())
                    .put("dealFlag", stateChange.dealFlag()).toString());
            eventInsert.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("state-change write failed", e);
        }
    }

    @Override
    public void flush() {
        if (pendingTicks == 0) {
            return;
        }
        try {
            tickInsert.executeBatch();
            pendingTicks = 0;
        } catch (SQLException e) {
            throw new PersistenceException("tick batch flush failed", e);
        }
    }

    @Override
    public void close() {
        flush();
        try {
            tickInsert.close();
            barUpsert.close();
            eventInsert.close();
            connection.close();
        } catch (SQLException e) {
            throw new PersistenceException("PostgresStore close failed", e);
        }
    }

    private short instrumentId(String epic) throws SQLException {
        Short cached = instrumentIds.get(epic);
        if (cached != null) {
            return cached;
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO instruments (epic) VALUES (?) ON CONFLICT (epic) DO NOTHING")) {
            insert.setString(1, epic);
            insert.executeUpdate();
        }
        short id = lookupId("SELECT id FROM instruments WHERE epic = ?", epic, "instrument");
        instrumentIds.put(epic, id);
        return id;
    }

    private short lookupId(String sql, String name, String kind) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException(
                            "unknown " + kind + " '" + name + "' — schema seeds are the"
                                    + " source of truth; refusing to invent one");
                }
                return result.getShort(1);
            }
        } catch (SQLException e) {
            throw new PersistenceException(kind + " lookup failed", e);
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
