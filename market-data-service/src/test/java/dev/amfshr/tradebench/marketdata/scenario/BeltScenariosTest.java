package dev.amfshr.tradebench.marketdata.scenario;

import static dev.amfshr.tradebench.marketdata.scenario.Events.DISCONNECTED;
import static dev.amfshr.tradebench.marketdata.scenario.Events.TRYING_RECOVERY;
import static dev.amfshr.tradebench.marketdata.scenario.Events.WILL_RETRY;
import static dev.amfshr.tradebench.marketdata.scenario.Events.bar;
import static dev.amfshr.tradebench.marketdata.scenario.Events.dbBroken;
import static dev.amfshr.tradebench.marketdata.scenario.Events.dbDown;
import static dev.amfshr.tradebench.marketdata.scenario.Events.dbWritesRefused;
import static dev.amfshr.tradebench.marketdata.scenario.Events.dbUp;
import static dev.amfshr.tradebench.marketdata.scenario.Events.hostSleep;
import static dev.amfshr.tradebench.marketdata.scenario.Events.igDown;
import static dev.amfshr.tradebench.marketdata.scenario.Events.reject;
import static dev.amfshr.tradebench.marketdata.scenario.Events.serverError;
import static dev.amfshr.tradebench.marketdata.scenario.Events.status;
import static dev.amfshr.tradebench.marketdata.scenario.Events.stop;
import static dev.amfshr.tradebench.marketdata.scenario.Events.streaming;
import static dev.amfshr.tradebench.marketdata.scenario.Events.subscribed;
import static dev.amfshr.tradebench.marketdata.scenario.Events.tick;
import static dev.amfshr.tradebench.marketdata.scenario.Events.ticks;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.marketdata.events.EventType;
import dev.amfshr.tradebench.marketdata.events.StreamState;

/**
 * The belt as one system, under scripted outages and one captured one, asserting chapter 10's
 * promises on the five observables (E1-T11). A scenario for a promise the code does not yet keep
 * is {@code @Disabled} naming its E1-T10 finding; its fix enables it as the exposing test.
 */
class BeltScenariosTest {

    private static final String DAX = "IX.D.DAX.DAILY.IP";
    private static final String NASDAQ = "IX.D.NASDAQ.CASH.IP";
    private static final String DAX_PRICE = "PRICE:AB12CE:" + DAX;
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
                .at(s(31), status(DISCONNECTED)) // the rebuilt connection is refused too
                .at(s(45), streaming()) // IG is back: the third connection streams
                .until(s(120)).build();

        Observed o = ScenarioRunner.run(overnight);

        assertEquals(List.of(0L, 30L, 35L), o.secondsOf("connect"),
                "the rebuild at once; the refusal at 31 is paced to exactly the 5s floor, and not forgotten");
        assertEquals(List.of(30L, 35L), o.secondsOf("disconnect"), "each dead session dropped first");
        assertEquals(2, o.events(EventType.CONNECTION_DEAD).size(), "one per death");
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
        // T10 A1: the SDK's order is the bare DISCONNECTED first, then onServerError explaining it.
        // Both are deaths; the first signal dates and describes the one death, the explanation is
        // its own record, the next sweep rebuilds once, the rebuilt pair is confirmed and capture
        // resumes. (The server error alone is a death too — SupervisorTest keeps that rule.)
        Scenario refused = Scenario.named("the server refuses the session").markets(DAX)
                .at(s(0), streaming()).at(s(0), subscribed(DAX))
                .every(s(1), s(1), s(30), t -> tick(DAX))
                .at(s(30), status(DISCONNECTED), serverError(2, "Requested Adapter Set not available"))
                .at(s(45), streaming()).at(s(45), subscribed(DAX))
                .every(s(1), s(46), s(90), t -> tick(DAX))
                .until(s(90)).build();

        Observed o = ScenarioRunner.run(refused);

        assertEquals(List.of(0L, 30L), o.secondsOf("connect"));
        assertEquals(1, o.events(EventType.IG_API_ERROR).size(), "the explanation is recorded");
        assertEquals(1, o.events(EventType.CONNECTION_DEAD).size(), "one death, two signals");
        Observed.Seen death = o.events(EventType.CONNECTION_DEAD).get(0);
        assertEquals("DISCONNECTED", death.detail().path("status").asText(), "dated and described by the first signal");
        assertTrue(death.detail().path("code").isMissingNode(), "the explanation that followed did not overwrite it");
        assertEquals(45L, o.events(EventType.RECONNECT).get(0).at().toSeconds());
        assertEquals(o.ticksDelivered, o.landed.size(), "capture resumed on the rebuilt pair");
        assertTrue(o.exits.isEmpty());
    }

    @Test
    void aFeedThatCannotBeRebuiltDiesByTheBudgetNotTheCeiling() throws Exception {
        // Chapter 10: the budget is a clock, not a count. IG becomes unreachable and the client hangs
        // in WILL-RETRY; the escalator asks for a rebuild at 180, no attempt connects, and the belt
        // keeps asking — paced 5,5,5,8,16,32 then 60s: fifteen attempts by 731 — until ten minutes
        // from the first, FEED_DEAD{budget} at 780. Never the ten-rebuild ceiling: that counts
        // rebuilds that connected.
        Scenario unreachable = Scenario.named("IG unreachable for good").markets(DAX)
                .at(s(0), streaming()).at(s(0), subscribed(DAX))
                .every(s(1), s(1), s(60), t -> tick(DAX))
                .at(s(60), igDown(), status(WILL_RETRY))
                .until(s(900)).build();

        Observed o = ScenarioRunner.run(unreachable);

        assertEquals(List.of("FEED_DEAD → exit(1)"), o.exits.stream().map(Observed.Exit::how).toList());
        assertEquals(780L, o.exits.get(0).at().toSeconds());
        assertEquals("budget", o.events(EventType.FEED_DEAD).get(0).detail().get("reason").asText());
        assertEquals(15, o.events(EventType.STUCK_SUBSTATE_ESCALATED).size(), "one reason row per attempt");
        assertEquals(List.of(0L), o.secondsOf("connect"), "nothing ever came up");
        assertTrue(o.events(EventType.WATCHDOG_STALE).isEmpty(), "stood down on a dead wire");
    }

    @Test
    void aHostSuspendIsAnnotatedNotTreatedAsAnOutage() throws Exception {
        // Chapter 7's discriminator at the shell: the host is suspended (a paused VM, a hibernated
        // instance) — the socket dies, the wall clock jumps sixteen minutes, the monotonic clock does
        // not; on resume the client reconnects by itself. No rebuild, no staleness alarm — one
        // RECONNECT that says the host slept.
        Scenario suspend = Scenario.named("host suspend").markets(DAX)
                .at(s(0), streaming()).at(s(0), subscribed(DAX))
                .every(s(1), s(1), s(60), t -> tick(DAX))
                .at(s(60), status(WILL_RETRY), hostSleep(Duration.ofMinutes(16)))
                .at(s(61), streaming())
                .every(s(1), s(62), s(180), t -> tick(DAX))
                .until(s(180)).build();

        Observed o = ScenarioRunner.run(suspend);

        assertEquals(List.of(0L), o.secondsOf("connect"), "the client resumed by itself — no rebuild");
        Observed.Seen resume = o.events(EventType.RECONNECT).get(0);
        assertTrue(resume.detail().get("hostSlept").asBoolean(), "sixteen minutes of wall, one second awake");
        assertTrue(o.events(EventType.WATCHDOG_STALE).isEmpty(), "the stopwatches were rebaselined, not alarmed");
        assertTrue(o.remedies("unsubscribe").isEmpty());
        assertEquals(o.ticksDelivered, o.landed.size(), "capture carried on");
        assertTrue(o.exits.isEmpty());
    }

    @Test
    void aHostThatWakesWithTheClientStuckIsRebuiltInAwakeTime() throws Exception {
        // T5 residual 3's other half: the client reports WILL-RETRY as the socket dies, then the
        // host is suspended sixteen minutes. On resume the wall clock says the hang is already
        // sixteen minutes old; the escalator's 120s run in awake time — the rebuild at 180, not at
        // once — and the watchdog, stood down on a dead wire, raises nothing for the jump either.
        Scenario stuckOnWake = Scenario.named("stuck after a suspend").markets(DAX)
                .at(s(0), streaming()).at(s(0), subscribed(DAX))
                .every(s(1), s(1), s(60), t -> tick(DAX))
                .at(s(60), status(WILL_RETRY), hostSleep(Duration.ofMinutes(16)))
                .at(s(181), streaming()).at(s(181), subscribed(DAX))
                .every(s(1), s(182), s(240), t -> tick(DAX))
                .until(s(240)).build();

        Observed o = ScenarioRunner.run(stuckOnWake);

        assertEquals(List.of(0L, 180L), o.secondsOf("connect"), "120s of awake time after the hang was reported");
        assertEquals(1, o.events(EventType.STUCK_SUBSTATE_ESCALATED).size());
        assertTrue(o.events(EventType.WATCHDOG_STALE).isEmpty(), "no alarm on a dead wire, no alarm for the jump");
        Observed.Seen resume = o.events(EventType.RECONNECT).get(0);
        assertFalse(resume.detail().get("replayed").asBoolean(), "a rebuild owes a heal");
        assertTrue(resume.detail().get("hostSlept").asBoolean(), "sixteen minutes of wall inside two of awake");
        assertEquals(o.ticksDelivered, o.landed.size());
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
    void oneMarketGoneSilentClimbsItsOwnLadderWhileItsNeighbourStreams() throws Exception {
        // Chapter 10, row one, the per-market half: DAX stops ticking at 60 while NASDAQ streams on.
        // Resubscribe at +90 (150), the grace doubles to 120 → resubscribe at +210 (270), the grace
        // doubles to 240 → the session is rebuilt at +450 (510). NASDAQ is never touched until then.
        Scenario silent = Scenario.named("one market silent, one streaming").markets(DAX, NASDAQ)
                .at(s(0), streaming()).at(s(0), subscribed(DAX)).at(s(0), subscribed(NASDAQ))
                .every(s(1), s(1), s(61), t -> tick(DAX))
                .every(s(1), s(1), s(520), t -> tick(NASDAQ))
                .every(s(60), s(60), s(520), t -> bar(NASDAQ))
                .at(s(151), subscribed(DAX)).at(s(271), subscribed(DAX)) // IG confirms each fresh pair
                .until(s(520)).build();

        Observed o = ScenarioRunner.run(silent);

        assertEquals(List.of(150L, 150L, 270L, 270L), o.secondsOf("unsubscribe"), "two surgical retries, DAX only");
        assertTrue(o.remedies("unsubscribe").stream().allMatch(r -> r.item().contains(DAX)));
        assertEquals(List.of(0L, 510L), o.secondsOf("connect"), "then the session");
        assertEquals(List.of("resubscribe", "resubscribe"),
                o.events(EventType.WATCHDOG_STALE).stream().filter(e -> e.detail() != null && e.detail().has("action"))
                        .map(e -> e.detail().get("action").asText()).toList());
        assertEquals(3, o.events(EventType.WATCHDOG_STALE).size(), "the rebuild is the third verdict");
        assertEquals(o.ticksDelivered + o.barsDelivered, o.landed.size(), "NASDAQ's capture never blinked");
        assertTrue(o.exits.isEmpty());
    }

    @Test
    void theScarReplaysAsCapturedAndTheHeartbeatSaysStreamingThroughTheSilence() throws Exception {
        // The 2026-08-04 scar through the JSONL loader: six minutes of the real feed, then both
        // markets fall silent within 584ms of each other while the session reads streaming. The
        // data invariants hold, nothing is remedied before the watchdog's 90s, and the heartbeat
        // says CONNECTED_STREAMING through the first minute of the silence — which is the scar.
        Scenario scar = Replays.load("/replays/2026-08-04-silent-while-connected.jsonl").build();

        Observed o = ScenarioRunner.run(scar);

        assertEquals(3177, o.ticksDelivered, "every tick in the fixture");
        assertEquals(10, o.barsDelivered, "five candles per market, sealed where the feed was alive");
        assertEquals(o.ticksDelivered + o.barsDelivered, o.landed.size(), "landed exactly once");
        assertEquals(0, o.ticksHeldAtEnd);
        assertTrue(o.events(EventType.BAR_GAP).isEmpty(), "consecutive candles — no gap");
        assertTrue(o.remedies.stream().filter(r -> r.at().toSeconds() > 0 && r.at().toSeconds() < 450).toList().isEmpty(),
                "quiet through the healthy stretch and the first 89s of the silence");
        assertTrue(o.heartbeats.stream().filter(h -> h.at().toSeconds() <= 420)
                .allMatch(h -> h.state() == StreamState.CONNECTED_STREAMING), "the socket was up; the feed was dead");
        assertTrue(o.exits.isEmpty());
    }

    @Test
    void theScarsTwoDeadMarketsEarnOneSessionVerdictAtNinetySeconds() throws Exception {
        // Chapter 10: ≥2 markets stale together → rebuild at once. NASDAQ's last tick is 584ms after
        // DAX's (360.584 vs 360.000). The session verdict does come at 451, the second market's 90s
        // mark — but at 450 DAX alone is stale and gets market surgery first: a wasted pair of
        // subscriptions and a misleading WATCHDOG_STALE{resubscribe} row, one sweep before the
        // session it belongs to is torn down. Two feeds dying within a second should earn one verdict.
        Scenario scar = Replays.load("/replays/2026-08-04-silent-while-connected.jsonl").build();

        Observed o = ScenarioRunner.run(scar);

        assertEquals(List.of(0L, 451L), o.secondsOf("connect"), "one session rebuild when the second market is 90s silent");
        assertTrue(o.events(EventType.WATCHDOG_STALE).stream()
                .noneMatch(e -> e.detail() != null && "resubscribe".equals(e.detail().path("action").asText())),
                "no market surgery — the two died together");
    }

    @Test
    void tryingRecoveryIsGivenItsThreeHundredSecondsBeforeAnyRebuild() throws Exception {
        // Chapter 10 / 08: in TRYING-RECOVERY the server may still complete a lossless replay, so
        // the escalator waits 300s. Two open markets go silent with it; the watchdog must stand
        // down while the connection is not streaming, or its 90s session verdict throws the
        // replay away at 150.
        Scenario patience = Scenario.named("trying recovery for 300s").markets(DAX, NASDAQ)
                .at(s(0), streaming()).at(s(0), subscribed(DAX)).at(s(0), subscribed(NASDAQ))
                .every(s(1), s(1), s(60), t -> tick(DAX)).every(s(1), s(1), s(60), t -> tick(NASDAQ))
                .at(s(60), status(TRYING_RECOVERY))
                .at(s(361), streaming()).at(s(361), subscribed(DAX)).at(s(361), subscribed(NASDAQ))
                .every(s(1), s(362), s(400), t -> tick(DAX)).every(s(1), s(362), s(400), t -> tick(NASDAQ))
                .until(s(400)).build();

        Observed o = ScenarioRunner.run(patience);

        assertEquals(List.of(0L, 360L), o.secondsOf("connect"), "the escalator's 300s, not the watchdog's 90s");
        assertTrue(o.events(EventType.WATCHDOG_STALE).isEmpty(), "stood down while not streaming");
        assertEquals(1, o.events(EventType.STUCK_SUBSTATE_ESCALATED).size());
    }

    @Test
    void aDeadSocketIsTheEscalatorsToRebuildAtOneHundredAndTwentySeconds() throws Exception {
        // ⑥ The socket dies; the SDK says WILL-RETRY and hangs. The escalator rebuilds at +120
        // (180). The watchdog must not resubscribe at +90 (150) on a connection with no session —
        // the market is not quiet, the wire is gone.
        Scenario deadSocket = Scenario.named("dead socket").markets(DAX)
                .at(s(0), streaming()).at(s(0), subscribed(DAX))
                .every(s(1), s(1), s(60), t -> tick(DAX))
                .at(s(60), status(WILL_RETRY))
                .at(s(181), streaming()).at(s(181), subscribed(DAX))
                .every(s(1), s(182), s(240), t -> tick(DAX))
                .until(s(240)).build();

        Observed o = ScenarioRunner.run(deadSocket);

        assertEquals(List.of(0L, 180L), o.secondsOf("connect"), "the escalator's 120s");
        assertTrue(o.events(EventType.WATCHDOG_STALE).isEmpty(), "no resubscribe on a dead wire");
        assertEquals(1, o.events(EventType.STUCK_SUBSTATE_ESCALATED).size());
        assertFalse(o.events(EventType.RECONNECT).get(0).detail().get("replayed").asBoolean());
        assertEquals(o.ticksDelivered, o.landed.size());
    }

    @Test
    void aRejectionHeldForAnUnconfirmedWitnessIsJudgedWhenTheWitnessConfirms() throws Exception {
        // §3.5: DAX strikes out at 3 while NASDAQ is still inside its confirm window — wait, never
        // race a fast rejection into a session verdict. When NASDAQ confirms at 20 the held verdict
        // is rendered at once: a confirmed witness means quarantine, not silence until the watchdog's
        // 90s. The verdict is dated when it is rendered (20), not when the strike landed (3) — a
        // choice chapter 10 does not make; the slice may rule otherwise.
        Scenario held = Scenario.named("a verdict held for its witness").markets(DAX, NASDAQ)
                .at(s(0), streaming())
                .at(s(1), reject(DAX, Event.Leg.PRICE, 40))
                .at(s(2), reject(DAX, Event.Leg.PRICE, 40))
                .at(s(3), reject(DAX, Event.Leg.PRICE, 40))
                .at(s(20), subscribed(NASDAQ))
                .every(s(1), s(21), s(120), t -> tick(NASDAQ))
                .until(s(120)).build();

        Observed o = ScenarioRunner.run(held);

        assertEquals(List.of(1L, 1L, 2L, 2L, 20L, 20L), o.secondsOf("unsubscribe"),
                "two surgical retries, then the pair dropped the moment the witness confirmed");
        assertEquals(20L, o.events(EventType.MARKET_QUARANTINED).get(0).at().toSeconds());
        assertTrue(o.events(EventType.WATCHDOG_STALE).isEmpty(), "not left to the watchdog");
        assertEquals(List.of(0L), o.secondsOf("connect"));
    }

    @Test
    void aVerdictHeldForAWitnessThatNeverConfirmsIsSessionShapedWhenTheWindowLapses() throws Exception {
        // §3.5's other arm: NASDAQ never confirms. When its 30s window lapses nothing proves the
        // session innocent, so the held verdict is session-shaped — a rebuild at 30, the rejected
        // market named — not silence until the watchdog's 90s.
        Scenario lapsed = Scenario.named("a verdict held for a witness that never comes").markets(DAX, NASDAQ)
                .at(s(0), streaming())
                .at(s(1), reject(DAX, Event.Leg.PRICE, 40))
                .at(s(2), reject(DAX, Event.Leg.PRICE, 40))
                .at(s(3), reject(DAX, Event.Leg.PRICE, 40))
                .at(s(31), streaming()).at(s(31), subscribed(DAX)).at(s(31), subscribed(NASDAQ))
                .every(s(1), s(32), s(120), t -> tick(DAX)).every(s(1), s(32), s(120), t -> tick(NASDAQ))
                .until(s(120)).build();

        Observed o = ScenarioRunner.run(lapsed);

        assertEquals(List.of(0L, 30L), o.secondsOf("connect"), "no witness when the window lapsed: session-shaped");
        assertEquals(DAX, o.events(EventType.SUBSCRIPTION_REJECTED).get(0).epic());
        assertTrue(o.events(EventType.WATCHDOG_STALE).isEmpty(), "not left to the watchdog");
    }

    @Test
    void aMarketWhoseBothLegsAreRejectedGetsItsTwoSurgicalRetries() throws Exception {
        // §3.5: initial subscribe + two surgical retries, a fresh pair each time. The server refuses
        // DAX outright, so both legs are rejected per attempt; that is one strike, not two. Each
        // answer is applied at the next sweep: the boot pair rejected → retry at 0, rejected → retry
        // at 1, rejected → the verdict at 2. Today the two legs count as two strikes, two
        // resubscribes go out in one sweep, and the verdict comes a sweep early.
        Scenario badEpic = Scenario.named("both legs rejected").markets(DAX, NASDAQ)
                .serverRejects(DAX, 40)
                .at(s(0), streaming()).at(s(0), subscribed(NASDAQ))
                .every(s(1), s(1), s(60), t -> tick(NASDAQ))
                .until(s(60)).build();

        Observed o = ScenarioRunner.run(badEpic);

        assertEquals(List.of(0L, 0L, 1L, 1L, 2L, 2L), o.secondsOf("unsubscribe"),
                "each attempt drops the pair before it: two retries, then the quarantine");
        assertEquals(List.of(0L, 0L, 0L, 0L, 0L, 0L, 1L, 1L), o.secondsOf("subscribe"),
                "boot subscribed both markets; one fresh DAX pair per retry — never two in one sweep");
        assertEquals(1, o.events(EventType.MARKET_QUARANTINED).size());
        assertEquals(2L, o.events(EventType.MARKET_QUARANTINED).get(0).at().toSeconds());
    }

    @Test
    void aWeekendZombieBehindAClosedFlagIsFoundByTheTwelveHourTeachingResubscribe() throws Exception {
        // D29 (1): the watchdog stands down on a market's last DLG_FLAG; a market that has read
        // CLOSED for twelve hours gets one teaching resubscribe, whose snapshot re-teaches the flag.
        // Here the item is dead server-side: nothing answers, the shell forgets the old flag, and
        // the ordinary ladder runs from the fresh subscription — +90, +210, then the session at +450.
        Scenario zombie = Scenario.named("weekend zombie").markets(DAX)
                .at(s(0), streaming()).at(s(0), subscribed(DAX))
                .at(s(1), tick(DAX, "CLOSED"))
                .until(Duration.ofHours(12).plusSeconds(700)).build();

        Observed o = ScenarioRunner.run(zombie);

        // The flag landed at the sweep at 1, so the twelve hours end at 43201.
        assertEquals(List.of(43_201L, 43_201L, 43_291L, 43_291L, 43_411L, 43_411L), o.secondsOf("unsubscribe"),
                "stood down through the night; the teaching resubscribe at 12h; then +90 and +210 from it");
        assertEquals(List.of(0L, 43_651L), o.secondsOf("connect"), "the zombie is found: the session at +450");
        assertEquals("STAND_DOWN_TEACHING", o.events(EventType.WATCHDOG_STALE).get(0).detail().get("signal").asText());
        assertEquals(4, o.events(EventType.WATCHDOG_STALE).size(), "the teach, two resubscribes, the rebuild");
    }

    @Test
    void aDatabaseThatRefusesWritesIsOneEpisodeClimbingTheLadder() throws Exception {
        // D28: a retryable failure is held for, backing off 5s → 60s, as one episode. A database
        // that accepts connections but refuses writes (disk full) must look like the blip at 100
        // → 190: one SINK_FAILURE, seven attempts, 131s — not a new episode every five seconds.
        Scenario diskFull = Scenario.named("writes refused").markets(DAX)
                .at(s(0), streaming()).at(s(0), subscribed(DAX))
                .every(s(1), s(1), s(300), t -> tick(DAX))
                .at(s(100), dbWritesRefused())
                .at(s(190), dbUp())
                .until(s(300)).build();

        Observed o = ScenarioRunner.run(diskFull);

        assertEquals(1, o.events(EventType.SINK_FAILURE).size(), "one episode, however many retries");
        assertEquals(7, o.events(EventType.SINK_FAILURE).get(0).detail().get("attempts").asInt());
        assertEquals(131_000L, o.events(EventType.SINK_FAILURE).get(0).detail().get("outageMs").asLong());
        assertEquals(o.ticksDelivered, o.landed.size());
    }

    @Test
    void aStopWithABacklogDrainsAllOfItIntoAHealthySink() throws Exception {
        // A stop lands with six thousand ticks queued and Postgres healthy: the tail must drain
        // all of them, not one TICK_BATCH, and a tick lost at shutdown must never be lost silently.
        Scenario backlog = Scenario.named("stop with a backlog").markets(DAX)
                .at(s(0), streaming()).at(s(0), subscribed(DAX))
                .at(s(10), ticks(DAX, 6_000))
                .at(s(10), stop())
                .until(s(20)).build();

        Observed o = ScenarioRunner.run(backlog);

        assertEquals(6_000, o.landed.size(), "every queued tick landed");
        assertTrue(o.log.stream().noneMatch(line -> line.contains("pump failure at shutdown")));
    }

    @Test
    void aStopDuringAHoldAfterPostgresReturnedRecoversOnceAndLandsEverything() throws Exception {
        // Postgres blinks at 30 and is back by 37; the operator stops at 38, before the next retry
        // at 40. One recovery attempt in the tail lands the held batch and the queued ticks — the
        // alternative is to forfeit data the database would have taken.
        Scenario ctrlC = Scenario.named("stop just after a blip").markets(DAX)
                .at(s(0), streaming()).at(s(0), subscribed(DAX))
                .every(s(1), s(1), s(60), t -> tick(DAX))
                .at(s(30), dbDown())
                .at(s(37), dbUp())
                .at(s(38), stop())
                .until(s(60)).build();

        Observed o = ScenarioRunner.run(ctrlC);

        assertEquals(o.ticksDelivered, o.landed.size(), "nothing forfeited that the database would have taken");
        assertEquals(0, o.ticksHeldAtEnd);
        assertTrue(o.log.stream().noneMatch(line -> line.contains("pump failure at shutdown")));
        assertEquals(1, o.events(EventType.SINK_FAILURE).size(), "the episode the tail ended is recorded like any other");
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
