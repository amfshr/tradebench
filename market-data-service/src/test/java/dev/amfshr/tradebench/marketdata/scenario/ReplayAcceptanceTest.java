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
    void theScarToTheCeilingCostsALoginEveryTenMinutesAtTheLimitAndNeverGivesUp() throws Exception {
        // Recorded: 60s of health, then both feeds dead within 584ms of the anchor (NASDAQ's last tick
        // at 60.056s) and silent for 23 minutes; the prototype sat CONNECTED:WS-STREAMING for 2h56m.
        // Modelled: every rebuilt connection answers "streaming". Ruled (E1-T12 ruling 4): the session
        // ladder climbs across such rebuilds — 60s → 120 → 240 → 480 → 600 (the cap) — and the belt
        // never gives up a feed that may return.
        Scenario scar = Replays.load("/replays/2026-08-04-silent-while-connected-ceiling.jsonl").build();

        Observed o = ScenarioRunner.accept(scar, database);

        assertEquals(List.of(0L, 151L, 271L, 511L, 991L), o.secondsOf("connect"),
                "the first sweep 90s after the later last tick (60.056s), then +120, +240, +480; the +600 falls past the window");
        assertEquals(4, o.events(EventType.WATCHDOG_STALE).size(), "one session verdict row per rebuild");
        assertTrue(o.events(EventType.FEED_DEAD).isEmpty(), "never given up");
        assertTrue(o.exits.isEmpty());
        assertEquals(distinctTicks(scar), o.landed.stream().filter(l -> l.startsWith("tick@")).count(),
                "every tick of the healthy minute is a row");
        assertEquals(0, o.barsDelivered, "no minute closed with the feed alive at its end — the fixture holds no candle");
        assertTrue(o.events(EventType.BAR_GAP).isEmpty());
    }

    @Test
    void daxThinAtDawnEarnsOneResubscribeAndTheGapTheRecordShows() throws Exception {
        // Recorded (2026-08-05): DAX silent 157s from the anchor (05:07:23.539Z), then 72s and 64s,
        // while NASDAQ ticked; the prototype's watchdog resubscribed DAX once (05:08:53.662Z, silent
        // 90.1s) and recorded bar_gap missing_bars 2 at 05:11:00Z. Only the first silence crosses 90s.
        Scenario dawn = Replays.load("/replays/2026-08-05-dax-thin-at-dawn.jsonl").build();

        Observed o = ScenarioRunner.accept(dawn, database);

        assertEquals(List.of(0L), o.secondsOf("connect"), "one market quiet is market surgery, never a session verdict");
        assertEquals(List.of(233L, 233L), o.remedies("subscribe").stream().filter(r -> r.at().toSeconds() > 0)
                .map(r -> r.at().toSeconds()).toList(),
                "the DAX pair re-asked for at the first sweep 90s after its last tick (143s) — the prototype's 05:08:53.662Z — and never again: 72s and 64s are under the line");
        assertTrue(o.remedies("subscribe").stream().filter(r -> r.at().toSeconds() > 0)
                .allMatch(r -> r.item().contains(DAX)), "NASDAQ, alive throughout, is left alone");
        List<Observed.Seen> gaps = o.events(EventType.BAR_GAP);
        assertEquals(1, gaps.size(), "one bar_gaps row");
        assertEquals(DAX, gaps.get(0).epic());
        assertEquals(2, gaps.get(0).detail().get("missingMinutes").asInt(), "05:08 and 05:09 had no DAX tick — the prototype's missing_bars 2");
        assertEquals("2026-08-05T05:08:00Z", gaps.get(0).detail().get("gapFromUtc").asText());
        assertEquals(distinctTicks(dawn), o.landed.stream().filter(l -> l.startsWith("tick@")).count());
        assertEquals(bars(dawn), o.landed.stream().filter(l -> l.startsWith("bar@")).count());
        assertTrue(o.exits.isEmpty());
    }

    @Test
    void daxQuietAtDawnIsMarketSurgeryWhileNasdaqIsLeftAlone() throws Exception {
        // Recorded (2026-08-10): NASDAQ wakes at the anchor (10s) and ticks ~200/min; DAX, silent since
        // before the window, stays silent 207s into it with the flag reading DEAL; the prototype
        // resubscribed DAX twice on a flat 90s grace. Ours doubles the grace, so the second re-ask
        // (not before 210s) is overtaken by DAX's return at 207.5s: one resubscribe, no session verdict.
        // Finding 1 (fixed here): DAX's return must not be judged bar-silent at 210s — its CHART clock
        // restarts with its ticks. No bar_gaps row: the window holds no DAX candle before the silence.
        Scenario dawn = Replays.load("/replays/2026-08-10-dax-quiet-at-dawn.jsonl").build();

        Observed o = ScenarioRunner.accept(dawn, database);

        assertEquals(List.of(0L), o.secondsOf("connect"));
        assertEquals(List.of(90L, 90L), o.remedies("subscribe").stream().filter(r -> r.at().toSeconds() > 0)
                .map(r -> r.at().toSeconds()).toList(), "DAX's pair, 90s after boot — its silence is older than the window");
        assertTrue(o.remedies("subscribe").stream().filter(r -> r.at().toSeconds() > 0)
                .allMatch(r -> r.item().contains(DAX)));
        assertTrue(o.events(EventType.BAR_GAP).isEmpty());
        assertEquals(distinctTicks(dawn), o.landed.stream().filter(l -> l.startsWith("tick@")).count());
        assertEquals(bars(dawn), o.landed.stream().filter(l -> l.startsWith("bar@")).count());
        assertTrue(o.exits.isEmpty());
    }

    @Test
    void aBusyTenMinutesLandsExactlyOnceWithNothingRemediedAndNothingRecorded() throws Exception {
        // Recorded (2026-09-23 11:00–11:10Z): 4,384 ticks, 16 candles, not one service event 10:50–11:20Z.
        Scenario busy = Replays.load("/replays/2026-09-23-busy-ten-minutes.jsonl").build();

        Observed o = ScenarioRunner.accept(busy, database);

        assertEquals(List.of(0L), o.secondsOf("connect"));
        assertTrue(o.remedies("subscribe").stream().noneMatch(r -> r.at().toSeconds() > 0), "no remedy");
        assertTrue(o.events.stream().allMatch(e -> e.type() == EventType.MARKET_STATE_CHANGE
                || e.type() == EventType.CONNECTION_STATUS),
                "the boot's status transition and the two DEAL flags are the only rows — nothing else was recorded");
        assertEquals(1, o.events(EventType.CONNECTION_STATUS).size(), "one connection, one transition");
        assertEquals(distinctTicks(busy), o.landed.stream().filter(l -> l.startsWith("tick@")).count(), "every distinct tick a row");
        assertEquals(bars(busy), o.landed.stream().filter(l -> l.startsWith("bar@")).count(), "every candle a row");
        assertEquals(2, rows("capture_status"));
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

    /** The fixture's own count of candles before the end — one row each, no dedupe to speak of. */
    private static long bars(Scenario scenario) {
        return scenario.steps.stream()
                .filter(s -> s.at().compareTo(scenario.until) < 0 && s.event() instanceof Event.Bar).count();
    }

    private static long rows(String table) throws SQLException {
        try (Connection c = database.dataSource().getConnection(); Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT count(*) FROM " + table)) {
            r.next();
            return r.getLong(1);
        }
    }
}
