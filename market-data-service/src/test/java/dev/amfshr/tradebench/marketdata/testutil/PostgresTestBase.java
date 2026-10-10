package dev.amfshr.tradebench.marketdata.testutil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.PostgreSQLContainer;

import dev.amfshr.tradebench.marketdata.store.Database;

/** One container + migrated pool per test class (manual lifecycle — no extension dep). */
public abstract class PostgresTestBase {

    protected static PostgreSQLContainer<?> postgres;
    protected static Database database;

    @BeforeAll
    protected static void startPostgres() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        database = Database.connect(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
    }

    @AfterAll
    protected static void stopPostgres() {
        database.close();
        postgres.stop();
    }
}
