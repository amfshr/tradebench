package dev.amfshr.tradebench.marketdata.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.jspecify.annotations.Nullable;

import dev.amfshr.tradebench.marketdata.coverage.GapDetector;
import dev.amfshr.tradebench.marketdata.events.CaptureStatus;
import dev.amfshr.tradebench.marketdata.events.ServiceEvent;

/**
 * The observability write path (E1-T5 slice B): events, bar gaps, and the health snapshot. Unlike
 * {@link PostgresStore} (one dedicated connection, the capture hot path), these writes come from
 * the pump (gaps + market-state, slice C step 3) and — as slice C wires them — the heartbeat and
 * supervisor threads, so each **borrows a pooled connection** for its one statement: low-frequency
 * work that must never contend with the hot path. The heartbeat's rows go as one batch on one
 * connection (E1-T10 #22). Thread-safe by that design; the instrument-id cache is concurrent.
 */
public final class PostgresObservabilityStore implements EventLog, GapStore, StatusStore {

    private static final String INSERT_EVENT = """
            INSERT INTO service_events (instance, user_id, source_id, instrument_id, category,
                event_type, severity, event_time_utc, correlation_id, detail)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)""";
    private static final String INSERT_GAP = """
            INSERT INTO bar_gaps (user_id, source_id, instrument_id, gap_from_utc, gap_to_utc,
                missing_minutes)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT ON CONSTRAINT bar_gaps_span DO NOTHING""";
    private static final String UPSERT_STATUS = """
            INSERT INTO capture_status (instance, instrument_id, updated_at_utc, stream_state,
                market_state, last_tick_at_utc, last_bar_at_utc, ticks_total, bars_total,
                dropped_ticks, malformed, reconnects_total, db_pending)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (instance, instrument_id) DO UPDATE SET
                updated_at_utc = EXCLUDED.updated_at_utc, stream_state = EXCLUDED.stream_state,
                market_state = EXCLUDED.market_state, last_tick_at_utc = EXCLUDED.last_tick_at_utc,
                last_bar_at_utc = EXCLUDED.last_bar_at_utc, ticks_total = EXCLUDED.ticks_total,
                bars_total = EXCLUDED.bars_total, dropped_ticks = EXCLUDED.dropped_ticks,
                malformed = EXCLUDED.malformed, reconnects_total = EXCLUDED.reconnects_total,
                db_pending = EXCLUDED.db_pending""";

    private final DataSource dataSource;
    private final String instance;
    private final short userId;
    private final short sourceId;
    private final InstrumentIds instruments = new InstrumentIds(new ConcurrentHashMap<>());

    public PostgresObservabilityStore(DataSource dataSource, String userName, String sourceName,
            String instance) {
        this.dataSource = dataSource;
        this.instance = instance;
        try (Connection connection = dataSource.getConnection()) {
            this.userId = Jdbc.lookupId(connection, "SELECT id FROM users WHERE name = ?", userName,
                    "user");
            this.sourceId = Jdbc.lookupId(connection, "SELECT id FROM sources WHERE name = ?",
                    sourceName, "source");
        } catch (SQLException e) {
            throw new PersistenceException("observability store initialisation failed", e);
        }
    }

    @Override
    public void write(ServiceEvent event) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(INSERT_EVENT)) {
            statement.setString(1, instance);
            statement.setShort(2, userId);
            statement.setShort(3, sourceId);
            setInstrument(statement, 4, connection, event.epic());
            statement.setString(5, event.category().db());
            statement.setString(6, event.type().db());
            statement.setString(7, event.severity().db());
            statement.setObject(8, Jdbc.utc(event.eventTimeUtc()));
            statement.setString(9, event.correlationId());
            statement.setString(10, event.detail() == null ? null : event.detail().toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("event write failed", e);
        }
    }

    @Override
    public void record(GapDetector.Gap gap) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(INSERT_GAP)) {
            statement.setShort(1, userId);
            statement.setShort(2, sourceId);
            statement.setShort(3, instruments.idFor(connection, gap.epic()));
            statement.setObject(4, Jdbc.utc(gap.gapFromUtc()));
            statement.setObject(5, Jdbc.utc(gap.gapToUtc()));
            statement.setInt(6, Math.toIntExact(gap.missingMinutes()));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("gap write failed", e);
        }
    }

    /** Every row in one batch on one connection: the heartbeat waits for the pool once (#22). */
    @Override
    public void upsert(List<CaptureStatus> rows) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(UPSERT_STATUS)) {
            for (CaptureStatus status : rows) {
                bind(statement, connection, status);
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException e) {
            throw new PersistenceException("status upsert failed", e);
        }
    }

    private void bind(PreparedStatement statement, Connection connection, CaptureStatus status)
            throws SQLException {
        statement.setString(1, instance);
        statement.setShort(2, instruments.idFor(connection, status.epic()));
        statement.setObject(3, Jdbc.utc(status.updatedAtUtc()));
        statement.setString(4, status.streamState().db());
        statement.setString(5, status.marketState());
        statement.setObject(6, Jdbc.utcOrNull(status.lastTickAtUtc()));
        statement.setObject(7, Jdbc.utcOrNull(status.lastBarAtUtc()));
        statement.setLong(8, status.ticksTotal());
        statement.setLong(9, status.barsTotal());
        statement.setLong(10, status.droppedTicks());
        statement.setLong(11, status.malformed());
        statement.setLong(12, status.reconnectsTotal());
        if (status.dbPending() != null) {
            statement.setInt(13, status.dbPending());
        } else {
            statement.setNull(13, Types.INTEGER);
        }
    }

    private void setInstrument(PreparedStatement statement, int index, Connection connection,
            @Nullable String epic) throws SQLException {
        if (epic == null) {
            statement.setNull(index, Types.SMALLINT);
        } else {
            statement.setShort(index, instruments.idFor(connection, epic));
        }
    }
}
