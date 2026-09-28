package dev.amfshr.tradebench.marketdata.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Advisory single-instance guard (playbook §7), taken BEFORE any IG contact: a double
 * launch would add a Lightstreamer connection on the account's shared key. Held on a
 * DEDICATED (non-pooled) session — a pooled connection would return to Hikari on close
 * still holding the session-level lock (doctrine-review finding, 2026-09-29); closing the
 * raw session is what releases it, and it also releases automatically if the process dies.
 */
public final class SingleInstanceLock implements AutoCloseable {

    /** Stable, documented key for market-data-service — one advisory key per service. */
    static final long LOCK_KEY = 0x7472_6462_0001L;

    private final Connection connection;

    private SingleInstanceLock(Connection connection) {
        this.connection = connection;
    }

    public static SingleInstanceLock acquire(Database database) {
        try {
            Connection connection = database.dedicatedConnection();
            try (PreparedStatement statement =
                    connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                statement.setLong(1, LOCK_KEY);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    if (!result.getBoolean(1)) {
                        connection.close();
                        throw new IllegalStateException(
                                "another market-data-service instance holds the advisory"
                                        + " lock — refusing to run (playbook §7)");
                    }
                }
            }
            return new SingleInstanceLock(connection);
        } catch (SQLException e) {
            throw new PersistenceException("advisory-lock acquisition failed", e);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new PersistenceException("advisory-lock release failed", e);
        }
    }
}
