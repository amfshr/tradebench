package dev.amfshr.tradebench.marketdata.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.jspecify.annotations.Nullable;

import dev.amfshr.tradebench.core.domain.Bar1m;
import dev.amfshr.tradebench.marketdata.heal.FlagChange;
import dev.amfshr.tradebench.marketdata.heal.HealStore;
import dev.amfshr.tradebench.marketdata.heal.OpenGap;
import dev.amfshr.tradebench.marketdata.heal.Run;
import dev.amfshr.tradebench.marketdata.heal.Window;
import dev.amfshr.tradebench.marketdata.supervise.ReconnectClassifier;

/**
 * The heal's reads and writes for one instance (E1-T6). It reads what the stream recorded — the
 * flags, the boots, the connection statuses, the bars and gaps — and writes healed bars under the
 * REST source, so a healed minute is always told from a streamed one (P8). The collector was
 * observing only while it was streaming: within each boot ({@code service_start} to the last sign
 * of life before the next — the latest tick or event row), the spans a recorded
 * {@code connection_status} says streaming (D27's one rule). A boot starts not streaming; history
 * recorded before either kind of row existed is taken as observed, as it was before them.
 * A pooled connection per call; the heal runs on the jobs thread only.
 */
public final class PostgresHealStore implements HealStore {

    private static final Instant FAR_FUTURE = Instant.parse("9999-12-31T00:00:00Z");

    private final DataSource dataSource;
    private final String userName;
    private final String restSourceName;
    private final String instance;
    private final short userId;
    private final short streamSourceId;
    private final short restSourceId;
    private final InstrumentIds instruments = new InstrumentIds(new ConcurrentHashMap<>());

    public PostgresHealStore(DataSource dataSource, String userName, String streamSourceName, String restSourceName,
            String instance) {
        this.dataSource = dataSource;
        this.userName = userName;
        this.restSourceName = restSourceName;
        this.instance = instance;
        try (Connection c = dataSource.getConnection()) {
            this.userId = Jdbc.lookupId(c, "SELECT id FROM users WHERE name = ?", userName, "user");
            this.streamSourceId = Jdbc.lookupId(c, "SELECT id FROM sources WHERE name = ?", streamSourceName, "source");
            this.restSourceId = Jdbc.lookupId(c, "SELECT id FROM sources WHERE name = ?", restSourceName, "source");
        } catch (SQLException e) {
            throw new PersistenceException("heal store initialisation failed", e);
        }
    }

    @Override
    public @Nullable Instant firstRecord(String epic) {
        return instant("""
                SELECT least((SELECT min(ts_utc) FROM ticks WHERE user_id = ? AND source_id = ? AND instrument_id = ?),
                             (SELECT min(start_utc) FROM bars_1m WHERE user_id = ? AND source_id = ? AND instrument_id = ?))""",
                epic, (s, id) -> {
                    s.setShort(1, userId);
                    s.setShort(2, streamSourceId);
                    s.setShort(3, id);
                    s.setShort(4, userId);
                    s.setShort(5, streamSourceId);
                    s.setShort(6, id);
                });
    }

    @Override
    public @Nullable String flagBefore(String epic, Instant at) {
        return query("""
                SELECT detail->>'dealFlag' FROM service_events
                WHERE event_type = 'market_state_change' AND user_id = ? AND source_id = ? AND instrument_id = ?
                  AND event_time_utc < ? ORDER BY event_time_utc DESC, id DESC LIMIT 1""", epic, (s, id) -> {
                    s.setShort(1, userId);
                    s.setShort(2, streamSourceId);
                    s.setShort(3, id);
                    s.setObject(4, Jdbc.utc(at));
                }, r -> r.next() ? r.getString(1) : null);
    }

    @Override
    public List<FlagChange> flagChanges(String epic, Instant from, Instant to) {
        return query("""
                SELECT event_time_utc, detail->>'dealFlag' FROM service_events
                WHERE event_type = 'market_state_change' AND user_id = ? AND source_id = ? AND instrument_id = ?
                  AND event_time_utc >= ? AND event_time_utc < ? ORDER BY event_time_utc, id""", epic, (s, id) -> {
                    s.setShort(1, userId);
                    s.setShort(2, streamSourceId);
                    s.setShort(3, id);
                    s.setObject(4, Jdbc.utc(from));
                    s.setObject(5, Jdbc.utc(to));
                }, r -> {
                    List<FlagChange> changes = new ArrayList<>();
                    while (r.next()) {
                        changes.add(new FlagChange(r.getObject(1, OffsetDateTime.class).toInstant(), r.getString(2)));
                    }
                    return changes;
                });
    }

    @Override
    public List<Window> processRuns(String epic, Instant from, Instant to) {
        return runs(epic).stream().map(r -> new Window(r.from(), r.to()))
                .filter(w -> w.to().isAfter(from) && w.from().isBefore(to)).toList();
    }

    @Override
    public List<Window> observed(String epic, Instant from, Instant to) {
        List<Window> windows = new ArrayList<>();
        for (ProcessRun run : runs(epic)) {
            streaming(epic, run.from(), run.to(), run.streamingAtStart(), windows);
        }
        return windows.stream().filter(w -> w.to().isAfter(from) && w.from().isBefore(to)).toList();
    }

    /** A boot to its last sign of life; a recorded boot starts not streaming, history before boots were
     * recorded (pre-E1-T6) is taken as streaming, as it was before the rows existed. */
    private record ProcessRun(Instant from, Instant to, boolean streamingAtStart) {
    }

    private List<ProcessRun> runs(String epic) {
        List<Instant> starts = query("""
                SELECT event_time_utc FROM service_events WHERE event_type = 'service_start' AND instance = ?
                ORDER BY event_time_utc""", epic, (s, id) -> s.setString(1, instance), r -> {
                    List<Instant> all = new ArrayList<>();
                    while (r.next()) {
                        all.add(r.getObject(1, OffsetDateTime.class).toInstant());
                    }
                    return all;
                });
        List<ProcessRun> runs = new ArrayList<>();
        if (starts.isEmpty()) {
            runs.add(new ProcessRun(Instant.EPOCH, FAR_FUTURE, true));
            return runs;
        }
        @Nullable Instant beforeFirst = lastSignBefore(epic, starts.getFirst());
        if (beforeFirst != null) {
            runs.add(new ProcessRun(Instant.EPOCH, beforeFirst, true));
        }
        for (int i = 0; i < starts.size(); i++) {
            Instant end = i + 1 < starts.size() ? lastSignBefore(epic, starts.get(i + 1)) : FAR_FUTURE;
            if (end != null && !end.isBefore(starts.get(i))) {
                runs.add(new ProcessRun(starts.get(i), end, false));
            }
        }
        return runs;
    }

    /** The streaming spans of one process window, from its recorded connection statuses. */
    private void streaming(String epic, Instant from, Instant to, boolean streamingAtStart, List<Window> into) {
        List<Instant> at = new ArrayList<>();
        List<Boolean> streams = new ArrayList<>();
        query("""
                SELECT event_time_utc, detail->>'status' FROM service_events
                WHERE event_type = 'connection_status' AND instance = ? AND event_time_utc >= ? AND event_time_utc <= ?
                ORDER BY event_time_utc, id""", epic, (s, id) -> {
                    s.setString(1, instance);
                    s.setObject(2, Jdbc.utc(from));
                    s.setObject(3, Jdbc.utc(to));
                }, r -> {
                    while (r.next()) {
                        at.add(r.getObject(1, OffsetDateTime.class).toInstant());
                        streams.add(ReconnectClassifier.isStreaming(r.getString(2)));
                    }
                    return null;
                });
        boolean streaming = streamingAtStart;
        Instant since = from;
        for (int i = 0; i < at.size(); i++) {
            if (streaming && !streams.get(i)) {
                into.add(new Window(since, at.get(i)));
            } else if (!streaming && streams.get(i)) {
                since = at.get(i);
            }
            streaming = streams.get(i);
        }
        if (streaming) {
            into.add(new Window(since, to));
        }
    }

    private @Nullable Instant lastSignBefore(String epic, Instant boot) {
        return instant("""
                SELECT greatest((SELECT max(ts_utc) FROM ticks WHERE user_id = ? AND source_id = ? AND instrument_id = ?
                                   AND ts_utc < ?),
                                (SELECT max(event_time_utc) FROM service_events WHERE instance = ? AND event_time_utc < ?))""",
                epic, (s, id) -> {
                    s.setShort(1, userId);
                    s.setShort(2, streamSourceId);
                    s.setShort(3, id);
                    s.setObject(4, Jdbc.utc(boot));
                    s.setString(5, instance);
                    s.setObject(6, Jdbc.utc(boot));
                });
    }

    @Override
    public Set<Instant> barMinutes(String epic, Instant from, Instant to) {
        return query("""
                SELECT start_utc FROM bars_1m WHERE user_id = ? AND instrument_id = ? AND start_utc >= ? AND start_utc < ?""",
                epic, (s, id) -> {
                    s.setShort(1, userId);
                    s.setShort(2, id);
                    s.setObject(3, Jdbc.utc(from));
                    s.setObject(4, Jdbc.utc(to));
                }, r -> {
                    Set<Instant> minutes = new HashSet<>();
                    while (r.next()) {
                        minutes.add(r.getObject(1, OffsetDateTime.class).toInstant());
                    }
                    return minutes;
                });
    }

    @Override
    public Set<Instant> healedMinutes(String epic, Instant from, Instant to) {
        return query("""
                SELECT start_utc FROM bars_1m WHERE user_id = ? AND source_id = ? AND instrument_id = ?
                  AND start_utc >= ? AND start_utc < ?""", epic, (s, id) -> {
                    s.setShort(1, userId);
                    s.setShort(2, restSourceId);
                    s.setShort(3, id);
                    s.setObject(4, Jdbc.utc(from));
                    s.setObject(5, Jdbc.utc(to));
                }, r -> {
                    Set<Instant> minutes = new HashSet<>();
                    while (r.next()) {
                        minutes.add(r.getObject(1, OffsetDateTime.class).toInstant());
                    }
                    return minutes;
                });
    }

    @Override
    public void writeHealed(List<Bar1m> bars) {
        if (bars.isEmpty()) {
            return;
        }
        PostgresStore healed = new PostgresStore(dataSource, userName, restSourceName);
        try {
            for (Bar1m bar : bars) {
                healed.write(bar);
            }
        } finally {
            healed.close();
        }
    }

    @Override
    public List<OpenGap> openGapsEnding(String epic, Instant from, Instant to) {
        return query("""
                SELECT id, gap_from_utc, gap_to_utc FROM bar_gaps WHERE user_id = ? AND instrument_id = ?
                  AND healed_at_utc IS NULL AND gap_to_utc >= ? AND gap_to_utc < ? ORDER BY gap_from_utc""",
                epic, (s, id) -> {
                    s.setShort(1, userId);
                    s.setShort(2, id);
                    s.setObject(3, Jdbc.utc(from));
                    s.setObject(4, Jdbc.utc(to));
                }, r -> {
                    List<OpenGap> gaps = new ArrayList<>();
                    while (r.next()) {
                        gaps.add(new OpenGap(r.getLong(1), r.getObject(2, OffsetDateTime.class).toInstant(),
                                r.getObject(3, OffsetDateTime.class).toInstant()));
                    }
                    return gaps;
                });
    }

    @Override
    public void closeGap(long id, Instant healedAt, String outcome) {
        update("UPDATE bar_gaps SET healed_at_utc = ?, heal_outcome = ? WHERE id = ?", s -> {
            s.setObject(1, Jdbc.utc(healedAt));
            s.setString(2, outcome);
            s.setLong(3, id);
        });
    }

    @Override
    public void recordHealedGap(String epic, Run run, Instant healedAt, String outcome) {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement("""
                INSERT INTO bar_gaps (user_id, source_id, instrument_id, gap_from_utc, gap_to_utc, missing_minutes,
                    healed_at_utc, heal_outcome)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT bar_gaps_span DO UPDATE SET
                    healed_at_utc = EXCLUDED.healed_at_utc, heal_outcome = EXCLUDED.heal_outcome""")) {
            s.setShort(1, userId);
            s.setShort(2, streamSourceId);
            s.setShort(3, instruments.idFor(c, epic));
            s.setObject(4, Jdbc.utc(run.from()));
            s.setObject(5, Jdbc.utc(run.to()));
            s.setLong(6, run.minutes());
            s.setObject(7, Jdbc.utc(healedAt));
            s.setString(8, outcome);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("bar_gaps write failed", e);
        }
    }

    // --- plumbing ---------------------------------------------------------------------------

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement, short instrumentId) throws SQLException;
    }

    @FunctionalInterface
    private interface Reader<T> {
        T read(ResultSet result) throws SQLException;
    }

    private <T> T query(String sql, String epic, Binder binder, Reader<T> reader) {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            binder.bind(s, instruments.idFor(c, epic));
            try (ResultSet r = s.executeQuery()) {
                return reader.read(r);
            }
        } catch (SQLException e) {
            throw new PersistenceException("heal read failed", e);
        }
    }

    private @Nullable Instant instant(String sql, String epic, Binder binder) {
        return query(sql, epic, binder, r -> {
            if (!r.next()) {
                return null;
            }
            OffsetDateTime t = r.getObject(1, OffsetDateTime.class);
            return t == null ? null : t.toInstant();
        });
    }

    @FunctionalInterface
    private interface Setter {
        void set(PreparedStatement statement) throws SQLException;
    }

    private void update(String sql, Setter setter) {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            setter.set(s);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("heal write failed", e);
        }
    }
}
