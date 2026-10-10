package dev.amfshr.tradebench.marketdata.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.jspecify.annotations.Nullable;

/**
 * The JDBC idioms the two stores share (E1-T10 #10). SQL failures pass through as
 * {@link SQLException}: each store's own catch decides what a failure means — the hot path breaks
 * its sink, the observability path throws a {@link PersistenceException} for the caller to count.
 */
final class Jdbc {

    private Jdbc() {
    }

    /** One seeded id by name. The schema seeds are the source of truth, so an unknown name is
     * refused, never invented. */
    static short lookupId(Connection on, String sql, String name, String kind) throws SQLException {
        try (PreparedStatement statement = on.prepareStatement(sql)) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException("unknown " + kind + " '" + name
                            + "' — schema seeds are the source of truth; refusing to invent one");
                }
                return result.getShort(1);
            }
        }
    }

    static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static @Nullable OffsetDateTime utcOrNull(@Nullable Instant instant) {
        return instant == null ? null : utc(instant);
    }
}
