package dev.amfshr.tradebench.marketdata.store;

import dev.amfshr.tradebench.marketdata.testutil.PostgresTestBase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.core.domain.Tick;

/**
 * E1-T7: the production roles script (deploy/postgres/roles.sql) run on a fresh database, then the service
 * connected as production will — migrating as the owner, running as the DML-only app role — and each
 * role's reach checked: the app writes rows but creates nothing; the reader reads and writes nothing.
 */
class DatabaseRolesTest extends PostgresTestBase {

    private static final String DB = "roles_check";
    private static String url;

    @BeforeAll
    static void createTheRolesAndMigrate() throws Exception {
        try (Connection admin = superuser(postgres.getJdbcUrl()); Statement s = admin.createStatement()) {
            s.execute("CREATE DATABASE " + DB);
        }
        url = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + DB + "$1");
        String script = Files.readString(Path.of("../deploy/postgres/roles.sql"))
                .replace(":'owner_password'", "'pw-owner'").replace(":'app_password'", "'pw-app'")
                .replace(":'reader_password'", "'pw-reader'").replace(":\"db\"", DB);
        try (Connection admin = superuser(url); Statement s = admin.createStatement()) {
            for (String statement : script.replaceAll("(?m)^--.*$", "").split(";")) {
                if (!statement.isBlank()) {
                    s.execute(statement);
                }
            }
        }
        Database.connect(url, "market_data_app", "pw-app", new Database.Credentials("market_data_owner", "pw-owner"))
                .close();
    }

    @Test
    void theOwnerMigratedAndOwnsWhatItCreated() throws Exception {
        try (Connection admin = superuser(url); Statement s = admin.createStatement();
             ResultSet r = s.executeQuery("SELECT tableowner FROM pg_tables WHERE tablename IN ('ticks', 'flyway_schema_history')")) {
            int n = 0;
            while (r.next()) {
                assertEquals("market_data_owner", r.getString(1));
                n++;
            }
            assertEquals(2, n);
        }
    }

    @Test
    void theAppWritesRowsButCreatesNothing() throws Exception {
        Database app = Database.connect(url, "market_data_app", "pw-app",
                new Database.Credentials("market_data_owner", "pw-owner"));
        try {
            PostgresStore store = new PostgresStore(app.dataSource(), "platform", "ig-stream-demo");
            store.write(new Tick("IX.D.DAX.DAILY.IP", Instant.parse("2026-10-17T10:00:00Z"), BigDecimal.ONE, BigDecimal.TWO));
            store.flush();
            store.close();
            try (Connection c = DriverManager.getConnection(url, "market_data_app", "pw-app"); Statement s = c.createStatement()) {
                SQLException refused = assertThrows(SQLException.class, () -> s.execute("CREATE TABLE sneaky (x int)"));
                assertEquals("42501", refused.getSQLState(), "no DDL for the running service");
            }
        } finally {
            app.close();
        }
    }

    @Test
    void theReaderReadsAndWritesNothing() throws Exception {
        try (Connection c = DriverManager.getConnection(url, "market_data_reader", "pw-reader"); Statement s = c.createStatement()) {
            try (ResultSet r = s.executeQuery("SELECT count(*) FROM bars_1m")) {
                assertTrue(r.next(), "the published data product reads");
            }
            SQLException refused = assertThrows(SQLException.class,
                    () -> s.execute("DELETE FROM users"));
            assertEquals("42501", refused.getSQLState());
        }
    }

    @Test
    void withoutAMigratorThePoolsOwnLoginMigratesAsBefore() {
        // the local case: one superuser, no separate owner — unchanged by E1-T7
        Database local = Database.connect(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        try (Connection c = local.dataSource().getConnection(); Statement s = c.createStatement();
             ResultSet result = s.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success")) {
            result.next();
            assertTrue(result.getInt(1) >= 6, "migrated by its own login, every version applied");
        } catch (SQLException e) {
            throw new AssertionError(e);
        } finally {
            local.close();
        }
    }

    private static Connection superuser(String jdbcUrl) throws SQLException {
        return DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword());
    }
}
