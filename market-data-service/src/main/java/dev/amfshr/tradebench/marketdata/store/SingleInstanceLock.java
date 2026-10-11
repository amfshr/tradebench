package dev.amfshr.tradebench.marketdata.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Advisory single-instance guard (playbook §7), taken BEFORE any IG contact: a double
 * launch would add a Lightstreamer connection on the account's shared key. Held on a
 * DEDICATED (non-pooled) session — a pooled connection would return to Hikari on close
 * still holding the session-level lock (doctrine-review finding, 2026-09-28); closing the
 * raw session is what releases it, and it also releases automatically if the process dies.
 *
 * <p>Re-verified every heartbeat ({@link #verify()}): a session-scoped lock dies with its session,
 * and a Postgres restart the sink holds through (D28) ends every session without the idle lock
 * connection ever learning it (E1-T13, the re-review's finding 1).
 */
public final class SingleInstanceLock implements AutoCloseable {

    /** Stable, documented key for market-data-service — one advisory key per service. */
    static final long LOCK_KEY = 0x7472_6462_0001L;

    /** Opens a raw, dedicated session — {@link Database#dedicatedConnection()} in production. */
    @FunctionalInterface
    interface SessionSource {
        Connection open() throws SQLException;
    }

    /** What a heartbeat's {@link #verify()} found. A refusal is not a standing: it throws. */
    public enum Standing {
        /** The session is alive, so it still holds the lock — nothing but session end releases it. */
        HELD,
        /** The session had died; a fresh one took the lock again. */
        REACQUIRED,
        /** The session had died and no fresh one can be opened: the database is down, so nobody can
         * take the lock either. Asked again next heartbeat — never an exit, which would drop what the
         * sink is holding. */
        UNVERIFIABLE
    }

    private final SessionSource sessions;
    private Connection connection;

    private SingleInstanceLock(SessionSource sessions, Connection connection) {
        this.sessions = sessions;
        this.connection = connection;
    }

    public static SingleInstanceLock acquire(Database database) {
        return acquire(database::dedicatedConnection);
    }

    static SingleInstanceLock acquire(SessionSource sessions) {
        return new SingleInstanceLock(sessions, lock(sessions));
    }

    /** A fresh session holding the lock. Refused (another instance holds it) throws
     * {@link IllegalStateException} with the session closed; a session that cannot be opened or
     * asked throws {@link PersistenceException}. */
    private static Connection lock(SessionSource sessions) {
        Connection connection;
        try {
            connection = sessions.open();
        } catch (SQLException e) {
            throw new PersistenceException("advisory-lock acquisition failed", e);
        }
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
        } catch (SQLException e) {
            closeQuietly(connection); // asked every heartbeat: a session it could not ask is not kept
            throw new PersistenceException("advisory-lock acquisition failed", e);
        }
        return connection;
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException e) {
            // the server has already let go of it, or will
        }
    }

    /**
     * The heartbeat's check. A live session still holds its lock, so liveness is the whole test; a
     * dead session is replaced by a fresh one that takes the lock again — or is refused, because
     * another instance took it while ours was gone: thrown as {@link IllegalStateException}, for the
     * caller to exit loud.
     */
    public synchronized Standing verify() {
        try {
            if (connection.isValid((int) Database.CONNECTION_TIMEOUT.toSeconds())) {
                return Standing.HELD;
            }
        } catch (SQLException e) {
            // a dead session answers nothing: replaced below
        }
        closeQuietly(connection); // a dead session: the server has already let go, and the lock with it
        try {
            connection = lock(sessions);
            return Standing.REACQUIRED;
        } catch (PersistenceException databaseDown) {
            return Standing.UNVERIFIABLE;
        }
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new PersistenceException("advisory-lock release failed", e);
        }
    }
}
