package dev.amfshr.tradebench.marketdata.persistence;

import java.sql.SQLException;

/** Unchecked wrapper for SQL failures — the pump's failure path treats these terminally. */
public final class PersistenceException extends RuntimeException {

    public PersistenceException(String message, SQLException cause) {
        super(message, cause);
    }
}
