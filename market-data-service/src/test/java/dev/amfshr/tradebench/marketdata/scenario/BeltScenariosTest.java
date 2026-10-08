package dev.amfshr.tradebench.marketdata.scenario;

import static dev.amfshr.tradebench.marketdata.scenario.Events.DISCONNECTED;
import static dev.amfshr.tradebench.marketdata.scenario.Events.WILL_RETRY;
import static dev.amfshr.tradebench.marketdata.scenario.Events.bar;
import static dev.amfshr.tradebench.marketdata.scenario.Events.dbBroken;
import static dev.amfshr.tradebench.marketdata.scenario.Events.dbDown;
import static dev.amfshr.tradebench.marketdata.scenario.Events.dbUp;
import static dev.amfshr.tradebench.marketdata.scenario.Events.hostSleep;
import static dev.amfshr.tradebench.marketdata.scenario.Events.reject;
import static dev.amfshr.tradebench.marketdata.scenario.Events.serverError;
import static dev.amfshr.tradebench.marketdata.scenario.Events.status;
import static dev.amfshr.tradebench.marketdata.scenario.Events.stop;
import static dev.amfshr.tradebench.marketdata.scenario.Events.streaming;
import static dev.amfshr.tradebench.marketdata.scenario.Events.subscribed;
import static dev.amfshr.tradebench.marketdata.scenario.Events.tick;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.marketdata.events.EventType;
import dev.amfshr.tradebench.marketdata.events.StreamState;

/**
 * The belt as one system, under scripted outages, asserting chapter 10's promises on the five
 * observables (E1-T11 increment 2 — the scenarios the code honours today; the ones it does not
 * yet are increment 3, each {@code @Disabled} naming its E1-T10 finding).
 */
class BeltScenariosTest {

    private static final String DAX = "IX.D.DAX.DAILY.IP";
    private static final String NASDAQ = "IX.D.NASDAQ.CASH.IP";
    private static final String DAX_PRICE = "PRICE:Z6CS3E:" + DAX;
    private static final String DAX_CHART = "CHART:" + DAX + ":1MINUTE";

    private static Duration s(long seconds) {
        return Duration.ofSeconds(seconds);
    }

    @Test
    void aQuietHealthyStretchIssuesNoRemedyAndLandsEveryWrite() throws Exception {
        Scenario quiet = Scenario.named("quiet healthy stretch").markets(DAX)
                .at(s(0), streaming()).at(s(0), subscribed(DAX))
                .every(s(1), s(1), s(180), t -> tick(DAX))
                .every(s(60), s(60), s(181), t -> bar(DAX))
                .until(s(185)).build();

        Observed o = ScenarioRunner.run(quiet);

        assertEquals(List.of(0L), o.secondsOf("connect"), "one boot, no rebuild");
        assertEquals(List.of(DAX_PRICE, DAX_CHART),
                o.remedies("subscribe").stream().map(Observed.Remedy::item).toList(), "the pair, once");
        assertTrue(o.remedies("unsubscribe").isEmpty());
        assertEquals(List.of(EventType.MARKET_STATE_CHANGE),
                o.events.stream().map(Observed.Seen::type).toList(), "the first DEAL flag is the only event");
        assertEquals(o.ticksDelivered + o.barsDelivered, o.landed.size(), "every tick and bar landed");
        assertEquals(0, o.ticksHeldAtEnd);
        assertEquals(List.of(60L, 120L, 180L),
                o.heartbeats(DAX).stream().map(h -> h.at().toSeconds()).toList());
        assertTrue(o.heartbeats(DAX).stream().allMatch(h -> h.state() == StreamState.CONNECTED_STREAMING));
        assertTrue(o.exits.isEmpty());
    }

    @Test
    void aBadMarketIsQuarantinedWhileItsWitnessStreams() throws Exception {
        // §3.5: three rejections of one market's pair while another market's pair is confirmed →
        // quarantine just that market; two surgical retries first. The pair's PRICE leg is the one
        // the server rejects each time (strikes count per leg — T10 #8 — so one leg per attempt).
        Scenario badEpic = Scenario.named("bad epic beside a healthy witness").markets(DAX, NASDAQ)
                .at(s(0), streaming()).at(s(0), subscribed(NASDAQ))
                .at(s(1), reject(DAX, Event.Leg.PRICE, 40))
                .at(s(2), reject(DAX, Event.Leg.PRICE, 40))
                .at(s(3), reject(DAX, Event.Leg.PRICE, 40))
                .every(s(1), s(1), s(90), t -> tick(NASDAQ))
                .until(s(90)).build();

        Observed o = ScenarioRunner.run(badEpic);

        assertEquals(List.of(0L), o.secondsOf("connect"), "market-shaped: never a session rebuild");
        assertEquals(List.of(1L, 1L, 2L, 2L, 3L, 3L), o.secondsOf("unsubscribe"),
                "two surgical retries drop the pair, then the quarantine drops it for good");
        assertEquals(List.of(0L, 0L, 0L, 0L, 1L, 1L, 2L, 2L), o.secondsOf("subscribe"),
                "boot subscribed both markets; each retry re-subscribed DAX; nothing after the verdict");
        assertEquals(1, o.events(EventType.MARKET_QUARANTINED).size());
        assertEquals(DAX, o.events(EventType.MARKET_QUARANTINED).get(0).epic());
        assertEquals(StreamState.QUARANTINED, o.heartbeats(DAX).get(0).state());
        assertEquals(StreamState.CONNECTED_STREAMING, o.heartbeats(NASDAQ).get(0).state(), "untouched");
        assertTrue(o.exits.isEmpty());
    }

    @Test
    void aBareDisconnectOvernightRebuildsAndTheResumeClosesTheOutage() throws Exception {
        // T10 #2: every market reads CLOSED, so the watchdog stands down; the client gives up for
        // good with a bare DISCONNECTED and no server error to explain it. Only the terminal rule
        // can rebuild.
        Scenario overnight = Scenario.named("the client gives up overnight").markets(DAX, NASDAQ)
                .at(s(0), streaming()).at(s(0), subscribed(DAX)).at(s(0), subscribed(NASDAQ))
                .at(s(1), tick(DAX, "CLOSED"), tick(NASDAQ, "CLOSED"))
                .at(s(30), status(DISCONNECTED))
                .at(s(45), streaming()) // IG is back: the rebuilt connection streams
                .until(s(120)).build();

        Observed o = ScenarioRunner.run(overnight);

        assertEquals(List.of(0L, 30L), o.secondsOf("connect"), "the rebuild, at once, paced as ever");
        assertEquals(List.of(30L), o.secondsOf("disconnect"), "the dead session dropped first");
        assertEquals(1, o.events(EventType.CONNECTION_DEAD).size());
        assertTrue(o.events(EventType.IG_API_ERROR).isEmpty(), "nothing explained it");
        assertTrue(o.events(EventType.WATCHDOG_STALE).isEmpty(), "not the watchdog's doing — it was stood down");
        Observed.Seen resume = o.events(EventType.RECONNECT).get(0);
        assertEquals(45L, resume.at().toSeconds());
        assertFalse(resume.detail().get("replayed").asBoolean(), "a rebuild tears the buffer down — a heal is owed");
        assertEquals(StreamState.CONNECTED_STREAMING, o.heartbeats(DAX).get(0).state());
        assertTrue(o.exits.isEmpty());
    }

    @Test
    void aServerRefusalIsADeathTooAndTheFirstSignalWins() throws Exception {
        // T10 A1: onServerError is a death as much as the bare DISCONNECTED the SDK sends after it.
        // The explanation is recorded, the death is latched once — from the first signal — and the
        // next sweep rebuilds; the rebuilt pair is confirmed and capture resumes.
        Scenario refused = Scenario.named("the server refuses the session").markets(DAX)
                .at(s(0), streaming()).at(s(0), subscribed(DAX))
                .every(s(1), s(1), s(30), t -> tick(DAX))
                .at(s(30), serverError(2, "Requested Adapter Set not available"), status(DISCONNECTED))
                .at(s(45), streaming()).at(s(45), subscribed(DAX))
                .every(s(1), s(46), s(90), t -> tick(DAX))
                .until(s(90)).build();

        Observed o = ScenarioRunner.run(refused);

        assertEquals(List.of(0L, 30L), o.secondsOf("connect"));
        assertEquals(1, o.events(EventType.IG_API_ERROR).size(), "the explanation is recorded");
        assertEquals(1, o.events(EventType.CONNECTION_DEAD).size(), "one death, two signals");
        assertEquals(2, o.events(EventType.CONNECTION_DEAD).get(0).detail().path("code").asInt(-1),
                "latched from the server error, not from the status that followed");
        assertEquals(45L, o.events(EventType.RECONNECT).get(0).at().toSeconds());
        assertEquals(o.ticksDelivered, o.landed.size(), "capture resumed on the rebuilt pair");
        assertTrue(o.exits.isEmpty());
    }

    @Test
    void aLidCloseIsAnnotatedNotTreatedAsAnOutage() throws Exception {
        // Chapter 7's discriminator at the shell: the socket dies as the lid closes, the wall clock
        // jumps sixteen minutes, the monotonic clock does not; on wake the client reconnects by
        // itself. No rebuild, no staleness alarm — one RECONNECT that says the host slept.
        Scenario lidClose = Scenario.named("lid close").markets(DAX)
                .at(s(0), streaming()).at(s(0), subscribed(DAX))
                .every(s(1), s(1), s(60), t -> tick(DAX))
                .at(s(60), status(WILL_RETRY), hostSleep(Duration.ofMinutes(16)))
                .at(s(61), streaming())
                .every(s(1), s(62), s(180), t -> tick(DAX))
                .until(s(180)).build();

        Observed o = ScenarioRunner.run(lidClose);

        assertEquals(List.of(0L), o.secondsOf("connect"), "the client resumed by itself — no rebuild");
        Observed.Seen resume = o.events(EventType.RECONNECT).get(0);
        assertTrue(resume.detail().get("hostSlept").asBoolean(), "sixteen minutes of wall, one second awake");
        assertTrue(o.events(EventType.WATCHDOG_STALE).isEmpty(), "the stopwatches were rebaselined, not alarmed");
        assertTrue(o.remedies("unsubscribe").isEmpty());
        assertEquals(o.ticksDelivered, o.landed.size(), "capture carried on");
        assertTrue(o.exits.isEmpty());
    }

    @Test
    void aPostgresBlipHoldsTheDataAndRecoversWithNothingLost() throws Exception {
        // T9's drill as a fixture: Postgres away for 90s mid-capture. Bars stay queued, ticks are
        // held by the store, the pump backs off and recovers, everything lands; the heartbeat's
        // status writes fail meanwhile (Tier 2), and one SINK_FAILURE records the episode.
        Scenario blip = Scenario.named("postgres blip").markets(DAX)
                .at(s(0), streaming()).at(s(0), subscribed(DAX))
                .every(s(1), s(1), s(300), t -> tick(DAX))
                .every(s(60), s(60), s(301), t -> bar(DAX))
                .at(s(100), dbDown())
                .at(s(190), dbUp())
                .until(s(300)).build();

        Observed o = ScenarioRunner.run(blip);

        assertEquals(o.ticksDelivered + o.barsDelivered, o.landed.size(), "zero bars and zero ticks lost");
        assertEquals(0, o.ticksHeldAtEnd);
        assertEquals(1, o.events(EventType.SINK_FAILURE).size(), "one episode, however many retries");
        Observed.Seen episode = o.events(EventType.SINK_FAILURE).get(0);
        assertEquals(100L, episode.at().toSeconds(), "dated from the first failure, not the recovery");
        // The ladder (§3.2 reused, D28): 1,2,4,8,16,32,64 → floor 5s, cap 60s → 5,5,5,8,16,32,60;
        // attempts at 105,110,115,123,139,171 fail (Postgres is back at 190), the seventh at 231 lands.
        assertEquals(131_000L, episode.detail().get("outageMs").asLong());
        assertEquals(7, episode.detail().get("attempts").asInt());
        assertEquals(7, o.sinkRecoveries);
        assertEquals(2, o.statusFailures, "the heartbeats at 120 and 180 could not write (Tier 2)");
        // The heartbeat at 180 shows the operator the backlog: bars 120 and 180, ticks 101..180.
        assertEquals(82, o.heartbeats(DAX).get(2).dbPending());
        assertEquals(List.of(0L), o.secondsOf("connect"), "the stream never blinked");
        assertTrue(o.exits.isEmpty());
        assertTrue(o.summary.contains("sinkFailures=1"), o.summary);
    }

    @Test
    void aStreamBlinkDuringAHoldIsStillSupervised() throws Exception {
        // The two belts are independent: while the pump holds for Postgres, the supervisor keeps
        // sweeping, so a stream blink mid-outage is seen and classified on time. Its RECONNECT
        // breadcrumb cannot be written while the database is away — Tier 2: counted, never thrown
        // (D29 will queue these for after the recovery; this assertion flips with that slice).
        Scenario blink = Scenario.named("stream blink inside a database hold").markets(DAX)
                .at(s(0), streaming()).at(s(0), subscribed(DAX))
                .every(s(1), s(1), s(240), t -> tick(DAX))
                .at(s(100), dbDown())
                .at(s(170), status(WILL_RETRY))
                .at(s(185), streaming())
                .at(s(190), dbUp())
                .until(s(240)).build();

        Observed o = ScenarioRunner.run(blink);

        assertEquals(StreamState.RECONNECTING, o.heartbeats(DAX).get(2).state(),
                "at 180 the supervisor had already seen the blink — it swept through the hold");
        assertEquals(StreamState.CONNECTED_STREAMING, o.heartbeats(DAX).get(3).state());
        assertTrue(o.events(EventType.RECONNECT).isEmpty(), "the breadcrumb was refused by the database");
        assertTrue(o.summary.contains("eventWriteFailures=1"), o.summary);
        assertEquals(o.ticksDelivered, o.landed.size(), "and nothing was lost meanwhile");
        assertEquals(List.of(0L), o.secondsOf("connect"));
        assertTrue(o.exits.isEmpty());
    }

    @Test
    void aSchemaErrorIsTerminalAndTheProcessLeavesLoud() throws Exception {
        // D28: a failure the blip taxonomy cannot name is not held for. The pump dies at once,
        // DB_ERROR says why and what was queued, the exit hook runs the shutdown order — the stream
        // is torn down cleanly even as capture is declared void — and the tick that met the broken
        // schema is the only one that never landed.
        Scenario schema = Scenario.named("schema error").markets(DAX)
                .at(s(0), streaming()).at(s(0), subscribed(DAX))
                .every(s(1), s(1), s(120), t -> tick(DAX))
                .at(s(30), dbBroken())
                .until(s(120)).build();

        Observed o = ScenarioRunner.run(schema);

        assertEquals(1, o.exits.size());
        assertEquals(30L, o.exits.get(0).at().toSeconds(), "at once — no budget, no hold");
        assertTrue(o.exits.get(0).how().startsWith("pump died → exit(1)"), o.exits.get(0).how());
        Observed.Seen death = o.events(EventType.DB_ERROR).get(0);
        assertEquals(30L, death.at().toSeconds());
        assertTrue(death.detail().get("cause").asText().contains("tick write failed"), death.toString());
        assertEquals(List.of(30L), o.secondsOf("disconnect"), "the exit hook closed the stream");
        assertEquals(30, o.ticksDelivered, "nothing arrives after the exit");
        assertEquals(29, o.landed.size(), "every tick but the one that met the broken schema");
        assertTrue(o.log.stream().anyMatch(line -> line.contains("pump failure at shutdown")));
        assertTrue(o.events(EventType.SINK_FAILURE).isEmpty(), "not a blip — no hold, no recovery");
    }

    @Test
    void aStopMidHoldDrainsTheTailAndReportsWhatItCouldNotKeep() throws Exception {
        // Ctrl-C while Postgres is away: the shutdown order runs, the pump's tail meets the broken
        // sink, the loss is logged — and the process never calls its own death a death.
        Scenario ctrlC = Scenario.named("stop mid-hold").markets(DAX)
                .at(s(0), streaming()).at(s(0), subscribed(DAX))
                .every(s(1), s(1), s(60), t -> tick(DAX))
                .at(s(30), dbDown())
                .at(s(40), stop())
                .until(s(60)).build();

        Observed o = ScenarioRunner.run(ctrlC);

        assertEquals(List.of("stopped"), o.exits.stream().map(Observed.Exit::how).toList(),
                "a stop is not a death — no exit(1) from the pump");
        assertEquals(40L, o.exits.get(0).at().toSeconds());
        assertTrue(o.log.stream().anyMatch(line -> line.contains("pump failure at shutdown")),
                "what could not be written is said, not swallowed");
        assertTrue(o.log.stream().anyMatch(line -> line.contains("sink close failed")));
        assertTrue(o.landed.size() < o.ticksDelivered, "the ticks after the outage began did not land");
        assertTrue(o.events(EventType.SINK_FAILURE).isEmpty(), "no recovery, so no recovery is recorded");
    }
}
