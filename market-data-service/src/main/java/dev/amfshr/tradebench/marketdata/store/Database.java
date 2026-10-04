package dev.amfshr.tradebench.marketdata.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Pooled Postgres access; Flyway migrates at connect — no persistence ⇒ do not run. The pool waits
 * {@link #CONNECTION_TIMEOUT} for a connection, not Hikari's 30s: while Postgres is down a Tier-2
 * write or the sink's recovery fails fast into its caller's backoff, and the heartbeat is held at
 * most that long per market (ruled 2026-10-04, E1-T9).
 */
public final class Database implements AutoCloseable {

    static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(5);

    private final HikariDataSource dataSource;
    private final String jdbcUrl;
    private final String user;
    private final String password;

    private Database(HikariDataSource dataSource, String jdbcUrl, String user,
            String password) {
        this.dataSource = dataSource;
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
    }

    public static Database connect(String jdbcUrl, String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(4);
        config.setConnectionTimeout(CONNECTION_TIMEOUT.toMillis());
        HikariDataSource dataSource = new HikariDataSource(config);
        Flyway.configure().dataSource(dataSource).load().migrate();
        return new Database(dataSource, jdbcUrl, user, password);
    }

    public DataSource dataSource() {
        return dataSource;
    }

    /** A raw, non-pooled session — for locks whose lifetime must equal the connection's. */
    public Connection dedicatedConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, user, password);
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
