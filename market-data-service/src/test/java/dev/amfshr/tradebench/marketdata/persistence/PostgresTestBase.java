package dev.amfshr.tradebench.marketdata.persistence;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.PostgreSQLContainer;

/** One container + migrated pool per test class (manual lifecycle — no extension dep). */
abstract class PostgresTestBase {

    static PostgreSQLContainer<?> postgres;
    static Database database;

    @BeforeAll
    static void startPostgres() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        database = Database.connect(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
    }

    @AfterAll
    static void stopPostgres() {
        database.close();
        postgres.stop();
    }
}
