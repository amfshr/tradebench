package dev.amfshr.tradebench.marketdata.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The blip taxonomy (ruled 2026-10-04): known-transient SQLSTATE classes retry; all else stops. */
class PersistenceExceptionTest {

    @Test
    void retryableIsKnownTransientOnlyBySqlStateClass() {
        Map<String, Boolean> table = new LinkedHashMap<>();
        table.put("08006", true);  // connection failure — the socket died mid-statement
        table.put("08001", true);  // cannot establish — also the pool timeout's state
        table.put("57P01", true);  // admin shutdown: pg_terminate_backend, a restart
        table.put("57P03", true);  // cannot connect now — Postgres still starting
        table.put("53300", true);  // too many connections
        table.put("40001", true);  // serialization failure
        table.put("40P01", true);  // deadlock detected
        table.put("23505", false); // unique violation — data, not weather
        table.put("42P01", false); // undefined table — schema
        table.put("28P01", false); // invalid password — configuration
        table.put("22P02", false); // invalid text representation — data
        table.put("XX000", false); // internal error — unknown is terminal

        table.forEach((state, expected) -> assertEquals(expected,
                new PersistenceException("x", new SQLException("boom", state)).retryable(),
                "SQLSTATE " + state));
    }

    @Test
    void aMissingStateIsTerminal() {
        assertFalse(new PersistenceException("x", new SQLException("no state")).retryable(),
                "never retry what we cannot name");
    }

    @Test
    void thePoolsConnectionTimeoutIsRetryableWhateverStateItCarries() {
        assertTrue(new PersistenceException("x",
                new SQLTransientConnectionException("pool - request timed out")).retryable());
    }

    @Test
    void theFirstStateInTheCauseChainDecides() {
        SQLException batchFailure = new SQLException("batch failed", (String) null,
                new SQLException("An I/O error occurred while sending to the backend", "08006"));

        assertTrue(new PersistenceException("x", batchFailure).retryable(),
                "a batch failure wraps the I/O error that carries the state");
    }
}
