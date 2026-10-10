package dev.amfshr.tradebench.marketdata.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.marketdata.events.EventType;
import dev.amfshr.tradebench.marketdata.testutil.PostgresTestBase;

/**
 * The belt against real captured outages, through the real stores into a real Postgres (E1-T12):
 * the gold standard short of the live IG socket. Expectations are anchored on what the source
 * recorded — never on a reconstructed connection sequence (the T12 nod, 2026-10-10). Tagged out
 * of the default run: {@code ./gradlew :market-data-service:replayAcceptance}.
 */
@Tag("acceptance")
class ReplayAcceptanceTest extends PostgresTestBase {

    private static final String DAX = "IX.D.DAX.DAILY.IP";

    @BeforeEach
    void emptyDatabase() throws SQLException {
        try (Connection c = database.dataSource().getConnection(); Statement s = c.createStatement()) {
            s.execute("TRUNCATE ticks, bars_1m, service_events, bar_gaps, capture_status, instruments"
                    + " RESTART IDENTITY CASCADE");
        }
    }

    @Test
    void theScarLandsEveryTickAndBarExactlyOnceInPostgres() throws Exception {
        // Recorded: six healthy minutes, then both feeds dead within 584ms while the session read
        // streaming (chapter 10's scar). Modelled: the healthy server's answers. Claims: the rows.
        Scenario scar = Replays.load("/replays/2026-08-04-silent-while-connected.jsonl").build();

        Observed o = ScenarioRunner.accept(scar, database);

        assertEquals(3177, o.ticksDelivered, "every tick in the fixture");
        assertEquals(10, o.barsDelivered, "five candles per market, sealed where the feed was alive");
        assertEquals(distinctTicks(scar), o.landed.stream().filter(l -> l.startsWith("tick@")).count(),
                "every distinct tick is a row — ticks_dedupe makes a re-sent batch idempotent, so distinct is the claim");
        assertEquals(10, o.landed.stream().filter(l -> l.startsWith("bar@")).count(), "every candle is a row");
        assertTrue(o.events(EventType.BAR_GAP).isEmpty(), "consecutive candles — no gap row");
        assertEquals(List.of(0L, 451L), o.secondsOf("connect"),
                "one session rebuild when the second market is 90s silent (chapter 10) — anchor 360s + 90s + 584ms");
        List<Observed.Seen> verdicts = o.events(EventType.WATCHDOG_STALE);
        assertEquals(1, verdicts.size(), "the session verdict is one service_events row");
        assertEquals(451, verdicts.get(0).at().toSeconds(), "dated by the sweep that rendered it");
        assertEquals(2, rows("capture_status"), "the heartbeat wrote one row per market");
        assertTrue(o.exits.isEmpty());
    }

    @Test
    void databaseWeatherIsNotAcceptanceModesToScript() {
        // A real Postgres cannot be made to misbehave on cue; a replay that asks is a fixture error,
        // never answered by pretending (the T12 nod: model nothing that can be real).
        Scenario blip = Scenario.named("a database blip in acceptance mode").markets(DAX)
                .at(Duration.ofSeconds(1), new Event.DbDown())
                .until(Duration.ofSeconds(2)).build();

        ScenarioRunner.HarnessError refused = assertThrows(ScenarioRunner.HarnessError.class,
                () -> ScenarioRunner.accept(blip, database));

        assertTrue(refused.getMessage().contains("scripted-mode only"), refused.getMessage());
    }

    /** The fixture's own count of distinct (epic, instant, bid, ask) ticks before the end — the
     * dedupe key — computed from the replay lines, never from the store. */
    private static long distinctTicks(Scenario scenario) {
        Set<String> keys = new HashSet<>();
        for (Scenario.Step step : scenario.steps) {
            if (step.at().compareTo(scenario.until) < 0 && step.event() instanceof Event.Tick t) {
                keys.add(t.epic() + "|" + t.tsUtc() + "|" + t.bid() + "|" + t.ask());
            }
        }
        return keys.size();
    }

    private static long rows(String table) throws SQLException {
        try (Connection c = database.dataSource().getConnection(); Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT count(*) FROM " + table)) {
            r.next();
            return r.getLong(1);
        }
    }
}
