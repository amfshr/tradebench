package dev.amfshr.tradebench.marketdata.store;

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
}
