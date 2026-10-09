package dev.amfshr.tradebench.marketdata.store;

import java.sql.Connection;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariDataSource;

/** Pins the pool's ruled numbers — chapter 5 and chapter 10 cite them. */
class DatabaseTest extends PostgresTestBase {

    @Test
    void thePoolWaitsFiveSecondsForAConnectionNotHikarisThirty() {
        HikariDataSource pool = (HikariDataSource) database.dataSource();

        assertEquals(5_000, pool.getConnectionTimeout(),
                "E1-T9 ruling: a Tier-2 write or a sink recovery fails fast into backoff");
        assertEquals(4, pool.getMaximumPoolSize(), "tiny on purpose — chapter 5");
    }

    @Test
    void everyConnectionCarriesTheSocketTimeoutAndKeepAlive() throws SQLException {
        // D29 (2): a half-open connection must time out into a PersistenceException, never hang.
        HikariDataSource pool = (HikariDataSource) database.dataSource();
        assertEquals("30", pool.getDataSourceProperties().getProperty("socketTimeout"));
        assertEquals("true", pool.getDataSourceProperties().getProperty("tcpKeepAlive"));
        try (Connection pooled = pool.getConnection()) {
            assertEquals(30_000, pooled.getNetworkTimeout(), "the driver applied it to the socket");
        }
        try (Connection dedicated = database.dedicatedConnection()) {
            assertEquals(30_000, dedicated.getNetworkTimeout(), "the lock's own connection too");
        }
    }
}
