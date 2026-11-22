package dev.amfshr.tradebench.marketdata.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import dev.amfshr.tradebench.marketdata.archive.ArchivedTick;

/**
 * A restore's second step (D39, E1-T17): an archived market-day's ticks put back into {@code ticks}. The dump
 * leaves the ticks out; the Parquet archive holds them. Each tick is inserted against {@code ticks_dedupe}, so
 * a tick already there is skipped and a second reload changes nothing; afterwards the day is counted back from
 * the database, so a restore says whether every archived tick is home rather than trusting the inserts.
 */
public final class TickReload {

    /** One archived day, reloaded: how many ticks its file held, how many were new, how many the database now holds. */
    public record Day(LocalDate day, int inFile, int inserted, long inDatabase) {

        /** Every archived tick is in the database: an archived day holds no tick the database lacks. */
        public boolean whole() {
            return inDatabase >= inFile;
        }
    }

    private static final int BATCH = 5_000;

    private final DataSource db;
    private final String user;
    private final String epic;

    /** @param user the user every reloaded tick belongs to (the archive's first path segment) */
    public TickReload(DataSource db, String user, String epic) {
        this.db = db;
        this.user = user;
        this.epic = epic;
    }

    /** Puts one UTC day's archived ticks back; the day's bounds are the archiver's, [00:00, 24:00). */
    public Day reload(LocalDate day, List<ArchivedTick> ticks) {
        try (Connection c = db.getConnection()) {
            short userId = Jdbc.lookupId(c, "SELECT id FROM users WHERE name = ?", user, "user");
            short instrumentId = new InstrumentIds(new HashMap<>()).idFor(c, epic);
            Map<String, Short> sources = new HashMap<>();
            c.setAutoCommit(false);
            int inserted = 0;
            try (PreparedStatement s = c.prepareStatement("""
                    INSERT INTO ticks (user_id, source_id, instrument_id, ts_utc, bid, ask) VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT ON CONSTRAINT ticks_dedupe DO NOTHING""")) {
                int pending = 0;
                for (ArchivedTick t : ticks) {
                    Short sourceId = sources.get(t.source());
                    if (sourceId == null) {
                        sourceId = Jdbc.lookupId(c, "SELECT id FROM sources WHERE name = ?", t.source(), "source");
                        sources.put(t.source(), sourceId);
                    }
                    s.setShort(1, userId);
                    s.setShort(2, sourceId);
                    s.setShort(3, instrumentId);
                    s.setObject(4, Jdbc.utc(t.tsUtc()));
                    s.setBigDecimal(5, t.bid());
                    s.setBigDecimal(6, t.ask());
                    s.addBatch();
                    if (++pending == BATCH) {
                        inserted += sum(s.executeBatch());
                        pending = 0;
                    }
                }
                if (pending > 0) {
                    inserted += sum(s.executeBatch());
                }
            }
            c.commit();
            return new Day(day, ticks.size(), inserted, count(c, userId, sources, instrumentId, day));
        } catch (SQLException e) {
            throw new PersistenceException("reloading the ticks of " + epic + " " + day + " failed", e);
        }
    }

    private static int sum(int[] counts) {
        int total = 0;
        for (int n : counts) {
            total += Math.max(n, 0);
        }
        return total;
    }

    private long count(Connection c, short userId, Map<String, Short> sources, short instrumentId, LocalDate day)
            throws SQLException {
        Instant from = day.atStartOfDay().toInstant(ZoneOffset.UTC);
        long total = 0;
        for (short sourceId : sources.values()) {
            try (PreparedStatement s = c.prepareStatement("""
                    SELECT count(*) FROM ticks WHERE user_id = ? AND source_id = ? AND instrument_id = ?
                    AND ts_utc >= ? AND ts_utc < ?""")) {
                s.setShort(1, userId);
                s.setShort(2, sourceId);
                s.setShort(3, instrumentId);
                s.setObject(4, Jdbc.utc(from));
                s.setObject(5, Jdbc.utc(from.plusSeconds(86_400)));
                try (ResultSet r = s.executeQuery()) {
                    r.next();
                    total += r.getLong(1);
                }
            }
        }
        return total;
    }
}
