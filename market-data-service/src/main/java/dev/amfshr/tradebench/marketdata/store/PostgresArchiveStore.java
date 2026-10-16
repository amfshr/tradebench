package dev.amfshr.tradebench.marketdata.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import dev.amfshr.tradebench.core.domain.OhlcPrices;
import dev.amfshr.tradebench.marketdata.archive.ArchiveStore;
import dev.amfshr.tradebench.marketdata.archive.ArchivedBar;
import dev.amfshr.tradebench.marketdata.archive.ArchivedTick;

/** The archive's reads (the day's ticks and bars) and its manifest ({@code archives}), for one user and stream source. */
public final class PostgresArchiveStore implements ArchiveStore {

    private final DataSource dataSource;
    private final short userId;
    private final short streamSourceId;
    private final InstrumentIds instruments = new InstrumentIds(new ConcurrentHashMap<>());

    public PostgresArchiveStore(DataSource dataSource, String userName, String streamSourceName) {
        this.dataSource = dataSource;
        try (Connection c = dataSource.getConnection()) {
            this.userId = Jdbc.lookupId(c, "SELECT id FROM users WHERE name = ?", userName, "user");
            this.streamSourceId = Jdbc.lookupId(c, "SELECT id FROM sources WHERE name = ?", streamSourceName, "source");
        } catch (SQLException e) {
            throw new PersistenceException("archive store initialisation failed", e);
        }
    }

    @Override
    public List<ArchivedTick> ticks(String epic, Instant from, Instant to) {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement("""
                SELECT t.ts_utc, t.bid, t.ask, so.name FROM ticks t JOIN sources so ON so.id = t.source_id
                WHERE t.user_id = ? AND t.source_id = ? AND t.instrument_id = ? AND t.ts_utc >= ? AND t.ts_utc < ?
                ORDER BY t.ts_utc, t.id""")) {
            s.setShort(1, userId);
            s.setShort(2, streamSourceId);
            s.setShort(3, instruments.idFor(c, epic));
            s.setObject(4, Jdbc.utc(from));
            s.setObject(5, Jdbc.utc(to));
            List<ArchivedTick> ticks = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    ticks.add(new ArchivedTick(r.getObject(1, OffsetDateTime.class).toInstant(), r.getBigDecimal(2),
                            r.getBigDecimal(3), r.getString(4)));
                }
            }
            return ticks;
        } catch (SQLException e) {
            throw new PersistenceException("archive tick read failed", e);
        }
    }

    @Override
    public List<ArchivedBar> bars(String epic, Instant from, Instant to) {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement("""
                SELECT b.start_utc, b.bid_o, b.bid_h, b.bid_l, b.bid_c, b.ask_o, b.ask_h, b.ask_l, b.ask_c, b.ltv, so.name
                FROM bars_1m b JOIN sources so ON so.id = b.source_id
                WHERE b.user_id = ? AND b.instrument_id = ? AND b.start_utc >= ? AND b.start_utc < ?
                ORDER BY b.start_utc, so.name""")) {
            s.setShort(1, userId);
            s.setShort(2, instruments.idFor(c, epic));
            s.setObject(3, Jdbc.utc(from));
            s.setObject(4, Jdbc.utc(to));
            List<ArchivedBar> bars = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    long ltv = r.getLong(10);
                    bars.add(new ArchivedBar(r.getObject(1, OffsetDateTime.class).toInstant(),
                            new OhlcPrices(r.getBigDecimal(2), r.getBigDecimal(3), r.getBigDecimal(4), r.getBigDecimal(5)),
                            new OhlcPrices(r.getBigDecimal(6), r.getBigDecimal(7), r.getBigDecimal(8), r.getBigDecimal(9)),
                            r.wasNull() ? null : ltv, r.getString(11)));
                }
            }
            return bars;
        } catch (SQLException e) {
            throw new PersistenceException("archive bar read failed", e);
        }
    }

    @Override
    public void record(String epic, LocalDate day, String kind, String objectKey, long bytes, String sha256) {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement("""
                INSERT INTO archives (user_id, source_id, instrument_id, kind, covers_date, object_key, bytes, sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, source_id, instrument_id, kind, covers_date) DO UPDATE SET
                    object_key = EXCLUDED.object_key, bytes = EXCLUDED.bytes, sha256 = EXCLUDED.sha256,
                    created_at_utc = now()""")) {
            s.setShort(1, userId);
            s.setShort(2, streamSourceId);
            s.setShort(3, instruments.idFor(c, epic));
            s.setString(4, kind);
            s.setObject(5, day);
            s.setString(6, objectKey);
            s.setLong(7, bytes);
            s.setString(8, sha256);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("archives write failed", e);
        }
    }
}
