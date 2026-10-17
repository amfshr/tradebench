package dev.amfshr.tradebench.marketdata.store;

import dev.amfshr.tradebench.marketdata.testutil.PostgresTestBase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.marketdata.jobs.JobRun;
import dev.amfshr.tradebench.marketdata.jobs.MarketDay;

/** job_runs as the runner reads and writes it: per job, per market-day, oldest first, with its dimensions. */
class PostgresJobRunStoreTest extends PostgresTestBase {

    private static final MarketDay DAX_D = new MarketDay("IX.D.DAX.DAILY.IP", LocalDate.of(2026, 10, 15));
    private static final Instant T = Instant.parse("2026-10-16T00:10:00Z");
    private PostgresJobRunStore store;

    @BeforeEach
    void clean() throws Exception {
        try (Connection c = database.dedicatedConnection(); Statement s = c.createStatement()) {
            s.execute("TRUNCATE job_runs RESTART IDENTITY");
        }
        // 'ig-stream-live' is source id 2: a swapped or dropped dimension is visible
        store = new PostgresJobRunStore(database.dataSource(), "platform", "ig-stream-live", "test-run");
    }

    @Test
    void runsComeBackForTheirJobAndMarketDayOldestFirst() {
        store.record(new JobRun("heal", DAX_D, T.plusSeconds(60), T.plusSeconds(61), true, "{\"healed\":2}"));
        store.record(new JobRun("heal", DAX_D, T, T.plusSeconds(1), false, "{\"error\":\"x\"}"));
        store.record(new JobRun("archive", DAX_D, T, T.plusSeconds(2), true, "{}"));
        store.record(new JobRun("heal", new MarketDay(DAX_D.epic(), DAX_D.day().plusDays(1)), T, T, true, "{}"));

        List<JobRun> heals = store.runs("heal", DAX_D);

        assertEquals(2, heals.size(), "only this job, only this market-day");
        assertFalse(heals.get(0).succeeded(), "oldest first");
        assertEquals(T, heals.get(0).startedUtc());
        assertTrue(heals.get(1).succeeded());
        assertEquals("{\"healed\": 2}", heals.get(1).detailJson(), "the detail round-trips as jsonb");
    }

    @Test
    void aRowCarriesItsUserSourceInstanceMarketAndDay() throws Exception {
        store.record(new JobRun("heal", DAX_D, T, T.plusSeconds(1), true, "{}"));
        try (Connection c = database.dedicatedConnection(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("""
                     SELECT u.name, so.name, j.instance, i.epic, j.covers_date, j.status
                     FROM job_runs j JOIN users u ON u.id = j.user_id JOIN sources so ON so.id = j.source_id
                     JOIN instruments i ON i.id = j.instrument_id""")) {
            assertTrue(r.next());
            assertEquals("platform", r.getString(1));
            assertEquals("ig-stream-live", r.getString(2));
            assertEquals("test-run", r.getString(3));
            assertEquals(DAX_D.epic(), r.getString(4));
            assertEquals(DAX_D.day(), r.getObject(5, LocalDate.class));
            assertEquals("succeeded", r.getString(6));
        }
    }
}
