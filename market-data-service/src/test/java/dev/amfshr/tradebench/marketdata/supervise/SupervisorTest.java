package dev.amfshr.tradebench.marketdata.supervise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.marketdata.testutil.FakeClock;
import dev.amfshr.tradebench.marketdata.testutil.RecordingEventLog;
import org.jspecify.annotations.Nullable;

import dev.amfshr.tradebench.ig.time.Sleeper;
import dev.amfshr.tradebench.marketdata.events.EventType;
import dev.amfshr.tradebench.marketdata.events.ServiceEvent;
import dev.amfshr.tradebench.marketdata.events.Severity;
import dev.amfshr.tradebench.marketdata.events.StreamState;
import dev.amfshr.tradebench.marketdata.store.BestEffortEventLog;

class SupervisorTest {

    private static final String STREAMING = "CONNECTED:WS-STREAMING";
    private static final String WILL_RETRY = "DISCONNECTED:WILL-RETRY";
    private static final String TRYING_RECOVERY = "DISCONNECTED:TRYING-RECOVERY";
    private static final String DAX = "IX.D.DAX.DAILY.IP";
    private static final String NASDAQ = "IX.D.NASDAQ.CASH.IP";

    private FakeClock clock;
    private RecordingEventLog events;
    private BestEffortEventLog beltEvents;
    private List<String> log;
    private FakeStream stream;
    private FakeFreshness freshness;
    private AtomicBoolean exhausted;
    private int giveUps;
    private Supervisor supervisor;

    @BeforeEach
    void setUp() {
        clock = new FakeClock(Instant.parse("2026-09-28T09:00:00Z"));
        events = new RecordingEventLog();
        log = new ArrayList<>();
        beltEvents = new BestEffortEventLog(events, log::add);
        stream = new FakeStream();
        freshness = new FakeFreshness();
        exhausted = new AtomicBoolean();
        giveUps = 0;
        supervisor = supervisorWith(Tuning.playbook());
    }

    @Test
    void replayedReconnectIsInfoAndResolvesNoGap() {
        supervisor.onStatusChange(stream.generation, TRYING_RECOVERY);
        clock.advance(Duration.ofSeconds(30));
        supervisor.onStatusChange(stream.generation, STREAMING);
        supervisor.sweep();

        ServiceEvent reconnect = single(EventType.RECONNECT);
        assertEquals(Severity.INFO, reconnect.severity());
        assertTrue(reconnect.detail().get("replayed").asBoolean());
        assertEquals(0, stream.rebuilds); // a replayed resume needs no rebuild
    }

    @Test
    void reconnectAfterWillRetryIsWarnAndFlagsAGap() {
        supervisor.onStatusChange(stream.generation, WILL_RETRY);
        clock.advance(Duration.ofSeconds(30));
        supervisor.onStatusChange(stream.generation, STREAMING);
        supervisor.sweep();

        ServiceEvent reconnect = single(EventType.RECONNECT);
        assertEquals(Severity.WARN, reconnect.severity());
        assertFalse(reconnect.detail().get("replayed").asBoolean()); // the outage's data is owed a heal
    }

    @Test
    void stuckSubstateForcesARebuildOnlyPastTheThreshold() {
        supervisor.onStatusChange(stream.generation, WILL_RETRY);
        supervisor.sweep();
        assertEquals(0, stream.rebuilds); // not yet — inside the 120s will-retry window

        clock.advance(Duration.ofSeconds(120));
        supervisor.sweep();
        assertEquals(1, stream.rebuilds);
        assertEquals(1, count(EventType.STUCK_SUBSTATE_ESCALATED));
    }


    @Test
    void rebuildsAreBackoffPacedThenGiveUpLoud() {
        // The rung ceiling in isolation: a one-day budget keeps the time budget out of the way.
        supervisor = supervisorWith(Tuning.playbook().withGiveUpAfter(Duration.ofDays(1)));
        int ceiling = Tuning.playbook().maxConsecutiveFailures();
        supervisor.onStatusChange(stream.generation, WILL_RETRY);
        supervisor.sweep(); // records the stuck substate

        for (int attempt = 0; attempt < ceiling; attempt++) {
            clock.advance(Duration.ofSeconds(120)); // clears both the threshold and any backoff spacing
            supervisor.sweep();
            supervisor.onStatusChange(stream.generation, WILL_RETRY); // the rebuilt connection hangs too
        }
        assertEquals(ceiling, stream.rebuilds);
        assertFalse(exhausted.get());

        clock.advance(Duration.ofSeconds(120));
        supervisor.sweep();
        assertTrue(exhausted.get());
        assertEquals(ceiling, stream.rebuilds); // no rebuild past the ceiling
        assertEquals(1, count(EventType.FEED_DEAD));
        assertEquals("ceiling", single(EventType.FEED_DEAD).detail().get("reason").asText());
    }

    @Test
    void recoveryGivesUpOnTheTimeBudgetExactlyAtTheBoundary() {
        Duration budget = Tuning.playbook().giveUpAfter();
        supervisor.onStatusChange(stream.generation, WILL_RETRY);
        supervisor.sweep();
        clock.advance(Duration.ofSeconds(120));
        supervisor.sweep(); // the first rebuild of this outage starts the budget clock
        assertEquals(1, stream.rebuilds);

        clock.advance(budget.minusSeconds(1));
        supervisor.sweep();
        assertFalse(exhausted.get(), "one second inside the budget: still recovering");
        int rebuildsBeforeGivingUp = stream.rebuilds;

        clock.advance(Duration.ofSeconds(1));
        supervisor.sweep();
        assertTrue(exhausted.get(), "the budget, not the rung ceiling, ends recovery");
        assertTrue(stream.rebuilds < Tuning.playbook().maxConsecutiveFailures());
        assertEquals(rebuildsBeforeGivingUp, stream.rebuilds, "no rebuild once given up");
        assertEquals("budget", single(EventType.FEED_DEAD).detail().get("reason").asText());

        clock.advance(Duration.ofSeconds(120));
        supervisor.sweep();
        assertEquals(1, count(EventType.FEED_DEAD), "given up once — never re-announced");
        assertEquals(rebuildsBeforeGivingUp, stream.rebuilds);
    }

    @Test
    void aResumeRestartsTheRecoveryBudget() {
        Duration budget = Tuning.playbook().giveUpAfter();
        supervisor.onStatusChange(stream.generation, WILL_RETRY);
        supervisor.sweep();
        clock.advance(Duration.ofSeconds(120));
        supervisor.sweep(); // outage 1: its first rebuild starts a budget clock
        assertEquals(1, stream.rebuilds);

        clock.advance(Duration.ofSeconds(300));
        supervisor.onStatusChange(stream.generation, STREAMING); // recovery succeeded — the ladder and budget reset
        supervisor.sweep();

        supervisor.onStatusChange(stream.generation, WILL_RETRY); // outage 2: a fresh ladder
        clock.advance(Duration.ofSeconds(120));
        supervisor.sweep();
        assertEquals(2, stream.rebuilds);

        clock.advance(budget.minusSeconds(1)); // well past outage 1's budget, one second inside outage 2's
        supervisor.sweep();
        assertFalse(exhausted.get(), "the budget runs from THIS outage's first rebuild");
        clock.advance(Duration.ofSeconds(1));
        supervisor.sweep();
        assertTrue(exhausted.get());
    }

    @Test
    void aRejectedConfigurationEndsRecoveryAtOnce() {
        stream.fatal = true; // the broker rejects the credentials/account on rebuild
        supervisor.onStatusChange(stream.generation, WILL_RETRY);
        supervisor.sweep();
        clock.advance(Duration.ofSeconds(120));
        supervisor.sweep();

        assertEquals(1, stream.rebuilds);
        assertTrue(exhausted.get(), "never climb a ladder against a lockout");
        assertEquals("fatal_config", single(EventType.FEED_DEAD).detail().get("reason").asText());

        clock.advance(Duration.ofSeconds(120));
        supervisor.sweep();
        assertEquals(1, stream.rebuilds, "latched — no further attempts");
    }

    @Test
    void aFailingEventWriteNeverStopsTheBelt() {
        events.failWrites = true; // Postgres is down — the breadcrumb cannot be written
        supervisor.onStatusChange(stream.generation, WILL_RETRY);
        supervisor.sweep();
        clock.advance(Duration.ofSeconds(120));
        supervisor.sweep(); // must not throw — the sweep thread must survive

        assertEquals(1, stream.rebuilds, "the remedy still happens; observability is downstream");
        assertEquals(1, beltEvents.failures(), "counted — loud, not silent");
    }

    @Test
    void everyEventTheBeltRecordsIsAlsoAConsoleLine() {
        // E1-T10 #9: the operator reads the console; the row may never land (the test above).
        supervisor.onServerError(stream.generation, 7, "licence lapsed");
        supervisor.sweep();

        assertEquals(1, count(EventType.IG_API_ERROR));
        assertEquals("belt IG_API_ERROR {\"code\":7,\"message\":\"licence lapsed\"}", log.get(0));
    }

    @Test
    void anInPlaceRetryIsAConsoleLine() {
        supervisor.watch(DAX);
        supervisor.onSubscriptionError(stream.generation, DAX, WitnessQuarantine.Kind.PRICE, 40, "rejected");
        supervisor.sweep(); // strike 1 → a surgical retry, which records no event — so it says so itself

        assertEquals(List.of(DAX), stream.resubscribed);
        assertEquals(List.of("belt RETRY " + DAX
                + " — re-asking for the pair in place after its PRICE leg was rejected (40)"), log);
    }

    @Test
    void aFailingEventWriteOnGiveUpStillHandsOverToTheRunner() {
        events.failWrites = true;
        stream.fatal = true;
        supervisor.onStatusChange(stream.generation, WILL_RETRY);
        supervisor.sweep();
        clock.advance(Duration.ofSeconds(120));
        supervisor.sweep();

        assertTrue(exhausted.get(), "an unwritable FEED_DEAD must not swallow the hand-over");
    }

    @Test
    void aWatchdogRebuildResetsTheLadderEvenWhenTheFarewellNeverArrives() {
        supervisor.watch(DAX);
        freshness.flag.put(DAX, "DEAL");
        supervisor.sweep();
        advanceAndSweep(Duration.ofSeconds(460)); // status STREAMING throughout — silent while connected
        assertEquals(1, stream.rebuilds);

        supervisor.onStatusChange(stream.generation, STREAMING); // the NEW connection comes up; the old farewell never arrives
        supervisor.sweep();

        ServiceEvent reconnect = single(EventType.RECONNECT);
        assertFalse(reconnect.detail().get("replayed").asBoolean(),
                "a rebuild tears the buffer down — the outage's data is owed a heal");
        for (int step = 0; step < 130; step++) { // 650s of a healthy feed, past the budget
            clock.advance(Duration.ofSeconds(5));
            freshness.tick.put(DAX, clock.monotonicNanos());
            freshness.bar.put(DAX, clock.monotonicNanos()); // bars too, or the dead-CHART signal fires
            supervisor.sweep();
        }
        assertFalse(exhausted.get(), "the resume reset the ladder — no spurious give-up");
        assertEquals(0, count(EventType.FEED_DEAD));
        assertEquals(1, stream.rebuilds);
    }

    @Test
    void theBeltViewReportsStreamingReconnectingAndQuarantined() {
        supervisor.watch(DAX);
        supervisor.watch(NASDAQ);
        assertEquals(StreamState.RECONNECTING, supervisor.stateOf(DAX), "nothing has streamed yet");

        supervisor.onStatusChange(stream.generation, STREAMING);
        supervisor.sweep();
        assertEquals(StreamState.CONNECTED_STREAMING, supervisor.stateOf(DAX));

        supervisor.onStatusChange(stream.generation, WILL_RETRY);
        supervisor.sweep();
        assertEquals(StreamState.RECONNECTING, supervisor.stateOf(DAX), "a drop is not streaming");

        supervisor.onStatusChange(stream.generation, STREAMING);
        supervisor.sweep();
        supervisor.onSubscribed(stream.generation, NASDAQ, WitnessQuarantine.Kind.PRICE);
        supervisor.onSubscribed(stream.generation, NASDAQ, WitnessQuarantine.Kind.CHART);
        failToTheStrikeCeiling(DAX);
        supervisor.sweep();
        assertEquals(StreamState.QUARANTINED, supervisor.stateOf(DAX));
        assertEquals(StreamState.CONNECTED_STREAMING, supervisor.stateOf(NASDAQ), "the witness streams on");
        assertEquals(1, supervisor.reconnectsTotal(), "one resume counted — the boot STREAMING is not a reconnect");
    }

    @Test
    void aConnectionStuckInStreamSensingDoesNotReadAsStreaming() {
        supervisor.onStatusChange(stream.generation, "CONNECTED:STREAM-SENSING");
        supervisor.sweep();
        assertEquals(StreamState.RECONNECTING, supervisor.stateOf(DAX), "the handshake is not data");

        supervisor.onStatusChange(stream.generation, "CONNECTED:HTTP-POLLING");
        supervisor.sweep();
        assertEquals(StreamState.CONNECTED_STREAMING, supervisor.stateOf(DAX),
                "a polling fallback still delivers data");
    }

    @Test
    void aQuarantinedMarketStaysQuarantinedThroughAnOutage() {
        supervisor.watch(DAX);
        supervisor.watch(NASDAQ);
        supervisor.onStatusChange(stream.generation, STREAMING);
        supervisor.sweep();
        supervisor.onSubscribed(stream.generation, NASDAQ, WitnessQuarantine.Kind.PRICE);
        supervisor.onSubscribed(stream.generation, NASDAQ, WitnessQuarantine.Kind.CHART);
        failToTheStrikeCeiling(DAX);
        supervisor.sweep();
        assertEquals(StreamState.QUARANTINED, supervisor.stateOf(DAX));

        supervisor.onStatusChange(stream.generation, WILL_RETRY);
        supervisor.sweep();
        assertEquals(StreamState.QUARANTINED, supervisor.stateOf(DAX), "quarantine outranks the connection state");
        assertEquals(StreamState.RECONNECTING, supervisor.stateOf(NASDAQ));
    }

    @Test
    void aRebuildReadsAsReconnectingUntilTheNewConnectionStreams() {
        supervisor.onStatusChange(stream.generation, STREAMING);
        supervisor.sweep();
        supervisor.watch(DAX);
        freshness.flag.put(DAX, "DEAL");
        supervisor.sweep();
        advanceAndSweep(Duration.ofSeconds(460)); // a watchdog-driven rebuild; status still reads STREAMING
        assertEquals(1, stream.rebuilds);
        assertEquals(StreamState.RECONNECTING, supervisor.stateOf(DAX),
                "mid-rebuild the old STREAMING is history, whatever the status says");

        supervisor.onStatusChange(stream.generation, STREAMING); // the new connection comes up
        supervisor.sweep();
        assertEquals(StreamState.CONNECTED_STREAMING, supervisor.stateOf(DAX));
    }

    @Test
    void reconnectsAreCounted() {
        assertEquals(0, supervisor.reconnectsTotal());
        supervisor.onStatusChange(stream.generation, WILL_RETRY);
        clock.advance(Duration.ofSeconds(30));
        supervisor.onStatusChange(stream.generation, STREAMING);
        supervisor.sweep();

        assertEquals(1, supervisor.reconnectsTotal());
    }

    @Test
    void aResumeOntoAPollingFallbackResetsTheLadder() {
        supervisor.onStatusChange(stream.generation, WILL_RETRY);
        supervisor.sweep();
        clock.advance(Duration.ofSeconds(120));
        supervisor.sweep(); // the first rebuild of this outage starts the budget clock
        assertEquals(1, stream.rebuilds);

        supervisor.onStatusChange(stream.generation, "CONNECTED:HTTP-POLLING"); // WebSocket blocked at the host; data still flows
        supervisor.sweep();

        assertEquals(1, count(EventType.RECONNECT), "a polling resume is a resume");
        assertEquals(1, count(EventType.TRANSPORT_DOWNGRADED), "and the degradation is recorded");
        clock.advance(Tuning.playbook().giveUpAfter().plusSeconds(1));
        supervisor.sweep();
        assertFalse(exhausted.get(), "the ladder reset — no spurious give-up on a flowing feed");
        assertEquals(StreamState.CONNECTED_STREAMING, supervisor.stateOf(DAX),
                "the view and the classifier agree on what resumed means");
    }







    @Test
    void twoMarketsStaleTogetherRebuildTheSession() {
        supervisor.watch(DAX);
        supervisor.watch(NASDAQ);
        freshness.flag.put(DAX, "DEAL");
        freshness.flag.put(NASDAQ, "DEAL");
        supervisor.sweep();
        advanceAndSweep(Duration.ofSeconds(95));

        assertEquals(1, stream.rebuilds); // several stale at once is never market noise (§3.4)
        assertTrue(stream.resubscribed.isEmpty());
    }

    @Test
    void aRealTickHealsThenRenewedSilenceGoesStaleOnce() {
        supervisor.watch(DAX);
        freshness.flag.put(DAX, "DEAL");
        supervisor.sweep(); // baseline
        clock.advance(Duration.ofSeconds(5));
        freshness.tick.put(DAX, clock.monotonicNanos()); // a real tick arrives
        supervisor.sweep(); // feeds it, heals — the market is live
        advanceAndSweep(Duration.ofSeconds(95)); // now it goes quiet

        // one resubscribe — the fixed last-seen must NOT be re-fed each sweep (that would heal forever)
        assertEquals(List.of(DAX), stream.resubscribed);
    }


    @Test
    void subscriptionFailingThriceWithNoWitnessRebuildsTheSession() {
        supervisor.watch(DAX); // the only market — nothing healthy to prove the session innocent
        failToTheStrikeCeiling(DAX);
        supervisor.sweep();

        assertEquals(1, stream.rebuilds); // session-shaped, so never quarantine the last market into silence
        assertTrue(stream.quarantined.isEmpty());
        assertEquals(DAX, single(EventType.SUBSCRIPTION_REJECTED).epic()); // the rejected market is named
    }

    @Test
    void aWitnessStillInsideItsConfirmWindowMakesTheFailureWait() {
        supervisor.watch(DAX);
        supervisor.watch(NASDAQ); // subscribe just started — not yet confirmed, still inside its window
        failToTheStrikeCeiling(DAX);
        supervisor.sweep();

        assertEquals(0, stream.rebuilds); // hold: never race a fast rejection into a whole-session rebuild
        assertTrue(stream.quarantined.isEmpty());
    }

    @Test
    void aRefusedUnsubscribeOnQuarantineForcesARebuild() {
        stream.quarantineRefused = true;
        supervisor.watch(DAX);
        supervisor.watch(NASDAQ);
        supervisor.onSubscribed(stream.generation, NASDAQ, WitnessQuarantine.Kind.PRICE);
        supervisor.onSubscribed(stream.generation, NASDAQ, WitnessQuarantine.Kind.CHART);
        failToTheStrikeCeiling(DAX);
        supervisor.sweep();

        assertEquals(List.of(DAX), stream.quarantined); // the unsubscribe was attempted
        assertEquals(1, stream.rebuilds); // but refused → double-delivery risk → rebuild
    }


    @Test
    void aRebuildResetsTheStrikesSoTheNewSessionIsJudgedAfresh() {
        supervisor.watch(DAX);
        failToTheStrikeCeiling(DAX);
        supervisor.sweep(); // no witness → rebuild #1
        assertEquals(1, stream.rebuilds);
        stream.resubscribed.clear();

        clock.advance(Duration.ofSeconds(10)); // past the 5s floor — pacing is not what decides here
        supervisor.onSubscriptionError(stream.generation, DAX, WitnessQuarantine.Kind.PRICE, 40, "rejected"); // the NEW session's first rejection
        supervisor.sweep();

        assertEquals(1, stream.rebuilds, "one strike in a fresh session is a retry, not a verdict");
        assertEquals(List.of(DAX), stream.resubscribed);
    }

    @Test
    void aRebuildReArmsTheSurvivorsSoAWitnessCanConfirmInTheNewSession() {
        supervisor.watch(DAX);
        supervisor.watch(NASDAQ);
        clock.advance(Duration.ofSeconds(31)); // NASDAQ's confirm window lapses unconfirmed
        failToTheStrikeCeiling(DAX);
        supervisor.sweep(); // no witness → rebuild #1
        assertEquals(1, stream.rebuilds);

        supervisor.onSubscribed(stream.generation, NASDAQ, WitnessQuarantine.Kind.PRICE); // the new session confirms NASDAQ
        supervisor.onSubscribed(stream.generation, NASDAQ, WitnessQuarantine.Kind.CHART);
        clock.advance(Duration.ofSeconds(10));
        failToTheStrikeCeiling(DAX);
        supervisor.sweep();

        assertEquals(List.of(DAX), stream.quarantined, "the re-armed witness proves the new session fine");
        assertEquals(1, stream.rebuilds);
    }

    @Test
    void aQuarantinedMarketIsForgottenNotNursedByTheWatchdog() {
        supervisor.watch(DAX);
        supervisor.watch(NASDAQ);
        supervisor.onSubscribed(stream.generation, NASDAQ, WitnessQuarantine.Kind.PRICE);
        supervisor.onSubscribed(stream.generation, NASDAQ, WitnessQuarantine.Kind.CHART);
        failToTheStrikeCeiling(DAX);
        supervisor.sweep();
        assertEquals(List.of(DAX), stream.quarantined);
        stream.resubscribed.clear(); // the two in-place retries before the verdict are not the point

        freshness.flag.put(DAX, "DEAL");      // open and silent — a watched market would be resubscribed
        freshness.flag.put(NASDAQ, "CLOSED"); // the witness stands down by its own flag
        supervisor.sweep();
        advanceAndSweep(Duration.ofSeconds(95));

        assertTrue(stream.resubscribed.isEmpty(), "quarantined means off the watch list too");
        assertEquals(0, stream.rebuilds);
    }

    @Test
    void theConfirmWindowStartsWhenTheRebuiltSessionSubscribesNotWhenTheRebuildBegan() {
        stream.duringRebuild = () -> clock.advance(Duration.ofSeconds(61)); // a paced re-login (LoginRateGate)
        supervisor.watch(DAX);
        supervisor.watch(NASDAQ);
        freshness.flag.put(DAX, "CLOSED");    // the watchdog is not under test: closed flags stand it
        freshness.flag.put(NASDAQ, "CLOSED"); // down, so the 61s jump cannot fire a staleness rebuild
        clock.advance(Duration.ofSeconds(31));
        failToTheStrikeCeiling(DAX);
        supervisor.sweep(); // no witness → rebuild #1, blocking 61s on the login gate
        assertEquals(1, stream.rebuilds);

        failToTheStrikeCeiling(DAX); // rejected again at once; NASDAQ's fresh subscribe is unconfirmed
        supervisor.sweep();

        assertEquals(1, stream.rebuilds,
                "NASDAQ is inside its fresh 30s confirm window: wait, never a second rebuild");
    }

    @Test
    void aQuarantinedMarketsTwinLegRejectionDoesNotReadmitIt() {
        supervisor.watch(DAX);
        supervisor.watch(NASDAQ);
        supervisor.onSubscribed(stream.generation, NASDAQ, WitnessQuarantine.Kind.PRICE);
        supervisor.onSubscribed(stream.generation, NASDAQ, WitnessQuarantine.Kind.CHART);
        failToTheStrikeCeiling(DAX);
        supervisor.onSubscriptionError(stream.generation, DAX, WitnessQuarantine.Kind.PRICE, 40, "rejected"); // the pair's other leg — queued before the verdict
        supervisor.sweep();

        assertEquals(List.of(DAX), stream.quarantined, "quarantined exactly once");
        assertEquals(2, stream.resubscribed.size(), "the two in-place retries before the verdict — none after");
        assertEquals(0, stream.rebuilds);
        assertEquals(1, count(EventType.MARKET_QUARANTINED));
    }

    @Test
    void theTerminalRuleIsExactlyTheBareDisconnected() {
        assertTrue(ReconnectClassifier.isTerminal("DISCONNECTED"), "the client gave up for good");
        assertFalse(ReconnectClassifier.isTerminal(WILL_RETRY), "the escalator's, not ours");
        assertFalse(ReconnectClassifier.isTerminal(TRYING_RECOVERY));
        assertFalse(ReconnectClassifier.isTerminal("CONNECTED:WS-STREAMING"));
        assertFalse(ReconnectClassifier.isTerminal("STALLED"));
    }


    @Test
    void aServerErrorAloneIsADeathToo() {
        supervisor.onServerError(stream.generation, 2, "Requested Adapter Set not available"); // §3.1: the first is a death

        supervisor.sweep();

        assertEquals(1, stream.rebuilds);
        assertEquals(2, single(EventType.CONNECTION_DEAD).detail().get("code").asInt());
        assertEquals(1, count(EventType.IG_API_ERROR), "and it is still recorded as itself");
    }


    @Test
    void aFlagLearntBeforeAResubscribeIsNotFedBackAfterIt() {
        // D29 (1): the remembered CLOSED belongs to the old subscription. Once the pair is re-asked
        // for, only a fresh tick's flag counts — so a market that answers nothing is open-unknown
        // and silent, and the watchdog acts instead of standing down forever.
        supervisor.watch(DAX);
        freshness.tick.put(DAX, clock.monotonicNanos());
        freshness.flag.put(DAX, "CLOSED");
        supervisor.sweep(); // stood down on the flag
        supervisor.onSubscriptionError(stream.generation, DAX, WitnessQuarantine.Kind.PRICE, 40, "rejected"); // strike 1 → a surgical retry re-asks the pair
        supervisor.sweep();
        assertEquals(List.of(DAX), stream.resubscribed);

        advanceAndSweep(Duration.ofSeconds(95)); // nothing answers; freshness still reports the old CLOSED

        assertEquals(List.of(DAX, DAX), stream.resubscribed, "the stale flag is not fed back — the watchdog acts at 90s");
        assertEquals(1, count(EventType.WATCHDOG_STALE));
    }

    @Test
    void aWatchdogResubscribeOpensAFreshConfirmWindow() {
        // E1-T10 #14: a market whose pair the watchdog just replaced is unconfirmed, inside a fresh
        // window — it cannot serve as the witness that quarantines its neighbour on stale evidence.
        supervisor.onStatusChange(stream.generation, STREAMING);
        supervisor.watch(DAX);
        supervisor.watch(NASDAQ);
        for (String epic : List.of(DAX, NASDAQ)) {
            supervisor.onSubscribed(stream.generation, epic, WitnessQuarantine.Kind.PRICE);
            supervisor.onSubscribed(stream.generation, epic, WitnessQuarantine.Kind.CHART);
            freshness.flag.put(epic, "DEAL");
        }
        supervisor.sweep();
        for (int step = 0; step < 19; step++) { // DAX falls silent; NASDAQ streams on
            clock.advance(Duration.ofSeconds(5));
            freshness.tick.put(NASDAQ, clock.monotonicNanos());
            supervisor.sweep();
        }
        assertEquals(List.of(DAX), stream.resubscribed, "the watchdog replaced DAX's pair at 90s");

        failToTheStrikeCeiling(NASDAQ); // and now the neighbour strikes out

        assertTrue(stream.quarantined.isEmpty(), "DAX's old confirmation is not a witness");
        assertEquals(0, stream.rebuilds, "held: DAX is inside its fresh confirm window");
        for (int step = 0; step < 7; step++) { // the window lapses with DAX never confirming
            clock.advance(Duration.ofSeconds(5));
            freshness.tick.put(NASDAQ, clock.monotonicNanos());
            supervisor.sweep();
        }
        assertEquals(1, stream.rebuilds, "no witness: session-shaped");
        assertEquals(NASDAQ, single(EventType.SUBSCRIPTION_REJECTED).epic());
    }

    @Test
    void aRebuildOpensTheOutageOnTheSweepsOwnClocks() {
        // E1-T10 #21: a rejection while streaming opens no outage until its sweep rebuilds; the
        // rejection's own wall stamp predates that sweep by the queue's latency, and opening the
        // outage with it against the sweep's monotonic stamp read as a host suspend.
        supervisor.onStatusChange(stream.generation, STREAMING);
        supervisor.watch(DAX); // the only market: no witness, so the third strike is session-shaped
        supervisor.sweep();
        for (int strike = 0; strike < 2; strike++) {
            clock.advance(Duration.ofMillis(1));
            supervisor.onSubscriptionError(stream.generation, DAX, WitnessQuarantine.Kind.PRICE, 40, "rejected");
            supervisor.sweep();
        }
        clock.advance(Duration.ofMillis(1));
        supervisor.onSubscriptionError(stream.generation, DAX, WitnessQuarantine.Kind.PRICE, 40, "rejected");
        clock.advance(Duration.ofSeconds(20)); // the queue drains late — the database was slow
        supervisor.sweep();
        assertEquals(1, stream.rebuilds);

        clock.advance(Duration.ofSeconds(5));
        supervisor.onStatusChange(stream.generation, STREAMING);
        supervisor.sweep();

        ServiceEvent resume = single(EventType.RECONNECT);
        assertEquals(5_000, resume.detail().get("wallOutageMs").asLong(), "dated from the rebuild");
        assertFalse(resume.detail().get("hostSlept").asBoolean(), "queue latency is not a suspend");
    }

    @Test
    void aFeedThatCannotBeRebuiltDiesByTheBudgetNotTheCeiling() {
        // E1-T10 #18: every rebuild fails to connect. The sweep keeps asking, paced 5,5,5,8,16,32,
        // 60…, and only rebuilds that connected count toward the ceiling — so the outage ends when
        // the ten-minute clock says so, with far more than ten attempts behind it.
        stream.failed = true;
        supervisor.onStatusChange(stream.generation, WILL_RETRY);
        supervisor.sweep();
        clock.advance(Duration.ofSeconds(120));
        supervisor.sweep(); // the escalator's rebuild — no connection comes up
        assertEquals(1, stream.rebuilds);

        Duration budget = Tuning.playbook().giveUpAfter();
        for (long elapsed = 5; elapsed < budget.toSeconds(); elapsed += 5) {
            clock.advance(Duration.ofSeconds(5));
            supervisor.sweep();
        }
        assertFalse(exhausted.get(), "five seconds inside the budget: still asking");
        assertTrue(stream.rebuilds > Tuning.playbook().maxConsecutiveFailures(),
                "the ceiling counts connected rebuilds, not failed attempts");

        clock.advance(Duration.ofSeconds(5));
        supervisor.sweep();
        assertTrue(exhausted.get());
        assertEquals("budget", single(EventType.FEED_DEAD).detail().get("reason").asText(), "the clock, not the count");
    }

    @Test
    void aSupersededConnectionsLastWordsAreAppliedToNothing() {
        // E1-T10 #11: the old connection's recovery completed and enqueued STREAMING just before the
        // rebuild bumped the generation. Applying it would close an outage that is still open.
        supervisor.onStatusChange(stream.generation, WILL_RETRY);
        supervisor.sweep();
        clock.advance(Duration.ofSeconds(120));
        supervisor.sweep(); // rebuild: generation 1 is the one that matters now
        assertEquals(1, stream.rebuilds);
        supervisor.onStatusChange(0, STREAMING); // the dead connection's greeting, stamped before the bump
        supervisor.sweep();

        assertTrue(ofType(EventType.RECONNECT).isEmpty(), "no phantom resume");
        assertEquals(StreamState.RECONNECTING, supervisor.stateOf(DAX));
        supervisor.onStatusChange(1, STREAMING); // the live connection's own
        supervisor.sweep();
        assertEquals(1, count(EventType.RECONNECT));
        assertEquals(StreamState.CONNECTED_STREAMING, supervisor.stateOf(DAX));
    }

    @Test
    void aGiveUpInsideTheDrainEndsTheSweep() {
        // E1-T10 #13: recovery ends on the first observation of the drain — the ceiling, reached
        // without a rebuild running — and the second must not be acted on: no retry against a
        // stream the runner is already tearing down, and one FEED_DEAD.
        supervisor = supervisorWith(Tuning.playbook().withGiveUpAfter(Duration.ofDays(1)));
        int ceiling = Tuning.playbook().maxConsecutiveFailures();
        supervisor.watch(DAX);
        supervisor.onStatusChange(stream.generation, WILL_RETRY);
        supervisor.sweep();
        for (int attempt = 0; attempt < ceiling; attempt++) { // ten rebuilds connect and hang: one ask from the ceiling
            clock.advance(Duration.ofSeconds(120));
            supervisor.sweep();
            supervisor.onStatusChange(stream.generation, WILL_RETRY);
        }
        assertEquals(ceiling, stream.rebuilds);
        clock.advance(Duration.ofSeconds(61)); // past the pacing, inside the escalator's 120s
        for (int strike = 0; strike < 2; strike++) {
            clock.advance(Duration.ofMillis(1));
            supervisor.onSubscriptionError(stream.generation, DAX, WitnessQuarantine.Kind.PRICE, 40, "rejected");
            supervisor.sweep();
        }
        clock.advance(Duration.ofMillis(1));
        supervisor.onSubscriptionError(stream.generation, DAX, WitnessQuarantine.Kind.PRICE, 40, "rejected"); // strike 3: no witness → rebuild → the ceiling
        supervisor.onSubscriptionError(stream.generation, NASDAQ, WitnessQuarantine.Kind.PRICE, 40, "rejected"); // queued behind it
        supervisor.sweep();

        assertTrue(exhausted.get());
        assertEquals("ceiling", single(EventType.FEED_DEAD).detail().get("reason").asText());
        assertEquals(List.of(DAX, DAX), stream.resubscribed, "NASDAQ's retry never happened — the sweep ended");
        assertEquals(1, giveUps);
    }

    @Test
    void anExceptionEscapingASweepDiesLoud() {
        // E1-T10 #19: the sweep thread must never die silently for a heartbeat to find a minute later.
        stream.resubscribeThrows = true;
        supervisor.watch(DAX);
        supervisor.onSubscriptionError(stream.generation, DAX, WitnessQuarantine.Kind.PRICE, 40, "rejected");

        assertThrows(IllegalStateException.class, supervisor::run,
                "the unit's own loop: the retry's resubscribe throws inside sweep() — and the trace still escapes");

        assertTrue(exhausted.get(), "the runner is told at once");
        ServiceEvent dead = single(EventType.FEED_DEAD);
        assertEquals("fatal", dead.detail().get("reason").asText());
        assertTrue(dead.detail().get("cause").asText().contains("subscribe exploded"), "and told why");
    }

    // --- helpers + fakes --------------------------------------------------------------------

    /** jitter = 1.0 → deterministic backoff; Sleeper/interval unused (tests drive sweep() directly). */
    private Supervisor supervisorWith(Tuning tuning) {
        return new Supervisor(clock, tuning, () -> 1.0, beltEvents, log::add, stream, freshness,
                () -> {
                    exhausted.set(true);
                    giveUps++;
                }, Sleeper.SYSTEM, Duration.ofSeconds(1));
    }

    /** Reject a market's pair attempt after attempt up to the strike ceiling — each rejection
     * stamped after the attempt it answers and applied by its own sweep, as on the wire — the
     * escalation point at which the witness rule renders its verdict. */
    private void failToTheStrikeCeiling(String epic) {
        for (int strike = 0; strike < Tuning.playbook().subscriptionStrikes(); strike++) {
            clock.advance(Duration.ofMillis(1));
            supervisor.onSubscriptionError(stream.generation, epic, WitnessQuarantine.Kind.PRICE, 40, "rejected");
            supervisor.sweep();
        }
    }

    /** Advance in ~5s steps (under the 10s freeze threshold) and sweep each, so elapsed time
     * accrues as in production rather than tripping the watchdog's process-freeze detector. */
    private void advanceAndSweep(Duration total) {
        for (long elapsed = 0; elapsed < total.toSeconds(); elapsed += 5) {
            clock.advance(Duration.ofSeconds(5));
            supervisor.sweep();
        }
    }

    private List<ServiceEvent> ofType(EventType type) {
        List<ServiceEvent> matches = new ArrayList<>();
        for (ServiceEvent event : events.written) {
            if (event.type() == type) {
                matches.add(event);
            }
        }
        return matches;
    }

    private int count(EventType type) {
        return ofType(type).size();
    }

    private ServiceEvent single(EventType type) {
        List<ServiceEvent> matches = ofType(type);
        assertEquals(1, matches.size(), "expected exactly one " + type);
        return matches.get(0);
    }



    private static final class FakeStream implements StreamControl {
        int rebuilds;
        int generation; // bumped by every rebuild, as the real control supersedes a connection
        boolean failed; // when true, rebuild() reports that no connection came up
        boolean resubscribeThrows; // when true, resubscribe() throws — a sweep that dies
        final List<String> resubscribed = new ArrayList<>();
        final List<String> quarantined = new ArrayList<>();
        boolean quarantineRefused; // when true, quarantine() reports a refused unsubscribe
        boolean fatal; // when true, rebuild() reports the broker rejected the configuration
        Runnable duringRebuild = () -> { }; // what the blocking rebuild does to the clock

        @Override
        public Outcome rebuild() {
            rebuilds++;
            duringRebuild.run();
            generation++;
            return fatal ? Outcome.FATAL : failed ? Outcome.FAILED : Outcome.CONNECTED;
        }

        @Override
        public int generation() {
            return generation;
        }

        @Override
        public void resubscribe(String epic) {
            if (resubscribeThrows) {
                throw new IllegalStateException("subscribe exploded");
            }
            resubscribed.add(epic);
        }

        @Override
        public boolean quarantine(String epic) {
            quarantined.add(epic);
            return !quarantineRefused;
        }
    }

    private static final class FakeFreshness implements MarketFreshness {
        final Map<String, Long> tick = new HashMap<>();
        final Map<String, Long> bar = new HashMap<>();
        final Map<String, String> flag = new HashMap<>();

        @Override
        public long lastTickMono(String epic) {
            return tick.getOrDefault(epic, Long.MIN_VALUE);
        }

        @Override
        public long lastBarMono(String epic) {
            return bar.getOrDefault(epic, Long.MIN_VALUE);
        }

        @Override
        public @Nullable String dealFlag(String epic) {
            return flag.get(epic);
        }
    }
}
