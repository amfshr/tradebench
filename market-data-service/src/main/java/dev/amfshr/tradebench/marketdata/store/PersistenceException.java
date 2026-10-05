package dev.amfshr.tradebench.marketdata.store;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Unchecked wrapper for SQL failures. {@link #retryable()} classifies the cause by its SQLSTATE
 * class — <b>known-transient only</b> (ruled 2026-10-04, E1-T9): connection loss (08), operator
 * intervention such as a shutdown or crash (57), insufficient resources (53), transaction
 * rollback (40), and the pool's own connection timeout. The pump holds and retries a retryable
 * failure; anything else — integrity, syntax, authorisation, data errors, an unknown or missing
 * state — is terminal and the pump fails closed at once.
 */
public final class PersistenceException extends RuntimeException {

    private static final Set<String> RETRYABLE_CLASSES = Set.of("08", "57", "53", "40");
    private static final int MAX_CAUSE_DEPTH = 8;

    private final boolean retryable;

    public PersistenceException(String message, SQLException cause) {
        super(message, cause);
        this.retryable = isRetryable(cause);
    }

    /** True when holding the data and retrying is the right response; false means stop. */
    public boolean retryable() {
        return retryable;
    }

    // The first SQLSTATE found walking the cause chain decides (a batch failure wraps the I/O
    // error that carries the state); a pool timeout is retryable whatever state it carries.
    static boolean isRetryable(SQLException cause) {
        @Nullable Throwable current = cause;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof SQLTransientConnectionException) {
                return true;
            }
            if (current instanceof SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().length() >= 2) {
                return RETRYABLE_CLASSES.contains(sql.getSQLState().substring(0, 2));
            }
            current = current.getCause();
        }
        return false; // unknown is terminal — never retry what we cannot name
    }
}
