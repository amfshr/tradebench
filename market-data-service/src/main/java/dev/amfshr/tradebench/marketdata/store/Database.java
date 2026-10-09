package dev.amfshr.tradebench.marketdata.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Properties;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Pooled Postgres access; Flyway migrates at connect — no persistence ⇒ do not run. The pool waits
 * {@link #CONNECTION_TIMEOUT} for a connection, not Hikari's 30s: while Postgres is down a Tier-2
 * write or the sink's recovery fails fast into its caller's backoff, and the heartbeat is held at
 * most that long per market (ruled 2026-10-04, E1-T9). Every connection — pooled or dedicated —
 * carries a {@link #SOCKET_TIMEOUT} and TCP keep-alive (D29 (2)): a half-open connection must
 * surface as a {@link PersistenceException} the hold can see, never a silent hang in a socket
 * read; our longest statement is a 500-row batch.
 */
public final class Database implements AutoCloseable {

    static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(5);
    static final Duration SOCKET_TIMEOUT = Duration.ofSeconds(30);

    private final HikariDataSource dataSource;
    private final String jdbcUrl;
    private final Properties connectionProperties;

    private Database(HikariDataSource dataSource, String jdbcUrl, Properties connectionProperties) {
        this.dataSource = dataSource;
        this.jdbcUrl = jdbcUrl;
        this.connectionProperties = connectionProperties;
    }

    public static Database connect(String jdbcUrl, String user, String password) {
        return connect(jdbcUrl, user, password, SOCKET_TIMEOUT);
    }

    /** As above with the socket timeout chosen — a test pauses the server and must not wait 30s. */
    static Database connect(String jdbcUrl, String user, String password, Duration socketTimeout) {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("socketTimeout", String.valueOf(socketTimeout.toSeconds()));
        properties.setProperty("tcpKeepAlive", "true");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(4);
        config.setConnectionTimeout(CONNECTION_TIMEOUT.toMillis());
        config.addDataSourceProperty("socketTimeout", properties.getProperty("socketTimeout"));
        config.addDataSourceProperty("tcpKeepAlive", "true");
        HikariDataSource dataSource = new HikariDataSource(config);
        Flyway.configure().dataSource(dataSource).load().migrate();
        return new Database(dataSource, jdbcUrl, properties);
    }

    public DataSource dataSource() {
        return dataSource;
    }

    /** A raw, non-pooled session — for locks whose lifetime must equal the connection's. */
    public Connection dedicatedConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, connectionProperties);
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
