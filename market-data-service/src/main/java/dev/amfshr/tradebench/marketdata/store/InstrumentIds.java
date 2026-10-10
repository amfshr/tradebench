package dev.amfshr.tradebench.marketdata.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;

/**
 * The instrument-id cache (E1-T10 #10): an epic's row is created on first sight — instruments are
 * discovered from the stream, not seeded — then looked up once and remembered. The caller chooses
 * the map: a plain one for the single-threaded hot path, a concurrent one for pooled writes.
 */
final class InstrumentIds {

    private final Map<String, Short> cache;

    InstrumentIds(Map<String, Short> cache) {
        this.cache = cache;
    }

    short idFor(Connection on, String epic) throws SQLException {
        Short cached = cache.get(epic);
        if (cached != null) {
            return cached;
        }
        try (PreparedStatement insert = on.prepareStatement(
                "INSERT INTO instruments (epic) VALUES (?) ON CONFLICT (epic) DO NOTHING")) {
            insert.setString(1, epic);
            insert.executeUpdate();
        }
        short id = Jdbc.lookupId(on, "SELECT id FROM instruments WHERE epic = ?", epic, "instrument");
        cache.put(epic, id);
        return id;
    }
}
