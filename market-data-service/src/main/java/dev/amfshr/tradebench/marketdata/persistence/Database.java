package dev.amfshr.tradebench.marketdata.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** Pooled Postgres access; Flyway migrates at connect — no persistence ⇒ do not run. */
public final class Database implements AutoCloseable {

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
