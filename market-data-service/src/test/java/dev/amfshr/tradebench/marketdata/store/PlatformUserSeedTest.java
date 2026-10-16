package dev.amfshr.tradebench.marketdata.store;

import dev.amfshr.tradebench.marketdata.testutil.PostgresTestBase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

/** E1-T14 (D31, R10): the seed user is renamed in place — same row, same id, nothing keyed by it moves. */
class PlatformUserSeedTest extends PostgresTestBase {

    @Test
    void theSeedUserIsPlatformWithTheOriginalId() throws Exception {
        try (Connection c = database.dedicatedConnection(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT id, name FROM users ORDER BY id")) {
            assertTrue(r.next(), "one seeded user");
            assertEquals(1, r.getInt("id"), "V1's row — renamed, not replaced");
            assertEquals("platform", r.getString("name"));
            assertFalse(r.next(), "no second user: the old name is gone, not duplicated");
        }
    }
}
