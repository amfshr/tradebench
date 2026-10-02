package dev.amfshr.tradebench.marketdata.supervise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.jspecify.annotations.Nullable;

import dev.amfshr.tradebench.core.time.Clock;
import dev.amfshr.tradebench.ig.time.Sleeper;
import dev.amfshr.tradebench.marketdata.events.EventType;
import dev.amfshr.tradebench.marketdata.events.ServiceEvent;
import dev.amfshr.tradebench.marketdata.events.Severity;
import dev.amfshr.tradebench.marketdata.store.EventLog;

class SupervisorTest {

    private static final String STREAMING = "CONNECTED:WS-STREAMING";
    private static final String WILL_RETRY = "DISCONNECTED:WILL-RETRY";
    private static final String TRYING_RECOVERY = "DISCONNECTED:TRYING-RECOVERY";
    private static final String DAX = "IX.D.DAX.DAILY.IP";
    private static final String NASDAQ = "IX.D.NASDAQ.CASH.IP";

    private FakeClock clock;
    private RecordingEvents events;
    private FakeStream stream;
    private FakeFreshness freshness;
    private AtomicBoolean exhausted;
    private Supervisor supervisor;

    @BeforeEach
    void setUp() {
        clock = new FakeClock(Instant.parse("2026-09-28T09:00:00Z"));
        events = new RecordingEvents();
        stream = new FakeStream();
        freshness = new FakeFreshness();
        exhausted = new AtomicBoolean();
        // jitter = 1.0 → deterministic backoff; Sleeper/interval unused (tests drive sweep() directly).
        supervisor = new Supervisor(clock, Tuning.playbook(), () -> 1.0, events, stream, freshness,
                () -> exhausted.set(true), Sleeper.SYSTEM, Duration.ofSeconds(1));
    }

    @Test
    void replayedReconnectIsInfoAndResolvesNoGap() {
        supervisor.onStatusChange(TRYING_RECOVERY);
        clock.advance(Duration.ofSeconds(30));
        supervisor.onStatusChange(STREAMING);
        supervisor.sweep();

        ServiceEvent reconnect = single(EventType.RECONNECT);
        assertEquals(Severity.INFO, reconnect.severity());
        assertTrue(reconnect.detail().get("replayed").asBoolean());
        assertEquals(0, stream.rebuilds); // a replayed resume needs no rebuild
    }

    @Test
    void reconnectAfterWillRetryIsWarnAndFlagsAGap() {
        supervisor.onStatusChange(WILL_RETRY);
        clock.advance(Duration.ofSeconds(30));
        supervisor.onStatusChange(STREAMING);
        supervisor.sweep();

        ServiceEvent reconnect = single(EventType.RECONNECT);
        assertEquals(Severity.WARN, reconnect.severity());
        assertFalse(reconnect.detail().get("replayed").asBoolean()); // the outage's data is owed a heal
    }

    @Test
    void stuckSubstateForcesARebuildOnlyPastTheThreshold() {
        supervisor.onStatusChange(WILL_RETRY);
        supervisor.sweep();
        assertEquals(0, stream.rebuilds); // not yet — inside the 120s will-retry window

        clock.advance(Duration.ofSeconds(120));
        supervisor.sweep();
        assertEquals(1, stream.rebuilds);
        assertEquals(1, count(EventType.STUCK_SUBSTATE_ESCALATED));
    }

    @Test
    void rebuildsHoldOffWithinTheBackoffWindow() {
        supervisor.onStatusChange(WILL_RETRY);
        supervisor.sweep();
        clock.advance(Duration.ofSeconds(120));
        supervisor.sweep(); // first rebuild; next is spaced by delayFor(1) = 5s (floor)
        assertEquals(1, stream.rebuilds);

        clock.advance(Duration.ofSeconds(3)); // still inside the 5s window
        supervisor.sweep();
        assertEquals(1, stream.rebuilds); // paced — no second rebuild

        clock.advance(Duration.ofSeconds(5)); // now past the window
        supervisor.sweep();
        assertEquals(2, stream.rebuilds);
    }

    @Test
    void rebuildsAreBackoffPacedThenGiveUpLoud() {
        int ceiling = Tuning.playbook().maxConsecutiveFailures();
        supervisor.onStatusChange(WILL_RETRY);
        supervisor.sweep(); // records the stuck substate

        for (int attempt = 0; attempt < ceiling; attempt++) {
            clock.advance(Duration.ofSeconds(120)); // clears both the threshold and any backoff spacing
            supervisor.sweep();
        }
        assertEquals(ceiling, stream.rebuilds);
        assertFalse(exhausted.get());

        clock.advance(Duration.ofSeconds(120));
        supervisor.sweep();
        assertTrue(exhausted.get());
        assertEquals(ceiling, stream.rebuilds); // no rebuild past the ceiling
        assertEquals(1, count(EventType.FEED_DEAD));
    }

    @Test
    void gracefulCloseHushesTheFarewellDisconnect() {
        supervisor.closing();
        supervisor.onStatusChange(WILL_RETRY);
        clock.advance(Duration.ofSeconds(30));
        supervisor.onStatusChange(STREAMING); // a resume that WOULD emit a reconnect, were it not hushed
        supervisor.sweep();

        assertTrue(ofType(EventType.RECONNECT).isEmpty()); // hushed: closing() meant no outage was opened
        assertEquals(0, stream.rebuilds);
    }

    @Test
    void hostSleepIsAnnotatedNotCountedAsAWildOutage() {
        supervisor.onStatusChange(TRYING_RECOVERY);
        clock.advanceWallOnly(Duration.ofMinutes(16)); // lid closed — wall jumps, monotonic frozen
        clock.advance(Duration.ofMillis(400));         // a sliver of real elapsed time on wake
        supervisor.onStatusChange(STREAMING);
        supervisor.sweep();

        ServiceEvent reconnect = single(EventType.RECONNECT);
        assertTrue(reconnect.detail().get("hostSlept").asBoolean());
        assertTrue(reconnect.detail().get("replayed").asBoolean());
    }

    @Test
    void openMarketGoneTickSilentIsResubscribed() {
        supervisor.watch(DAX);
        freshness.flag.put(DAX, "DEAL");
        supervisor.sweep(); // baseline the watchdog's per-round clock
        advanceAndSweep(Duration.ofSeconds(95)); // past the 90s tick-silent window, market still open

        assertEquals(List.of(DAX), stream.resubscribed);
        assertEquals(0, stream.rebuilds);
        assertEquals(1, count(EventType.WATCHDOG_STALE));
    }

    @Test
    void closedMarketSilenceStandsDown() {
        supervisor.watch(DAX);
        freshness.flag.put(DAX, "CLOSED");
        supervisor.sweep();
        advanceAndSweep(Duration.ofSeconds(95));

        assertTrue(stream.resubscribed.isEmpty()); // quiet because shut, not a dead feed
        assertEquals(0, stream.rebuilds);
        assertTrue(ofType(EventType.WATCHDOG_STALE).isEmpty());
    }

    @Test
    void hostSleepRebaselinesRatherThanAlarming() {
        supervisor.watch(DAX);
        freshness.flag.put(DAX, "DEAL");
        supervisor.sweep();
        clock.advanceWallOnly(Duration.ofMinutes(16)); // lid closed — wall jumps, monotonic frozen
        clock.advance(Duration.ofMillis(400));
        supervisor.sweep();

        assertTrue(stream.resubscribed.isEmpty());
        assertEquals(0, stream.rebuilds);
    }

    @Test
    void persistentSilenceEscalatesResubscribeThenRebuild() {
        supervisor.watch(DAX);
        freshness.flag.put(DAX, "DEAL");
        supervisor.sweep();
        advanceAndSweep(Duration.ofSeconds(460)); // crosses both resubscribe graces, then the rebuild

        assertEquals(2, stream.resubscribed.size()); // maxResubscribes, then it escalates
        assertEquals(1, stream.rebuilds);
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
    void subscriptionFailingThriceWithAHealthyWitnessIsQuarantinedNotRebuilt() {
        supervisor.watch(DAX);
        supervisor.watch(NASDAQ);
        supervisor.onSubscribed(NASDAQ, WitnessQuarantine.Kind.PRICE);
        supervisor.onSubscribed(NASDAQ, WitnessQuarantine.Kind.CHART); // a fully-confirmed witness
        failToTheStrikeCeiling(DAX);
        supervisor.sweep();

        assertEquals(List.of(DAX), stream.quarantined); // isolated — a confirmed peer proves the session fine
        assertEquals(0, stream.rebuilds);
        assertEquals(1, count(EventType.MARKET_QUARANTINED));
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
        supervisor.onSubscribed(NASDAQ, WitnessQuarantine.Kind.PRICE);
        supervisor.onSubscribed(NASDAQ, WitnessQuarantine.Kind.CHART);
        failToTheStrikeCeiling(DAX);
        supervisor.sweep();

        assertEquals(List.of(DAX), stream.quarantined); // the unsubscribe was attempted
        assertEquals(1, stream.rebuilds); // but refused → double-delivery risk → rebuild
    }

    // --- helpers + fakes --------------------------------------------------------------------

    /** Enqueue a market's subscription rejection up to the strike ceiling — the escalation point
     * at which the witness rule renders its verdict. */
    private void failToTheStrikeCeiling(String epic) {
        for (int strike = 0; strike < Tuning.playbook().subscriptionStrikes(); strike++) {
            supervisor.onSubscriptionError(epic, 40, "rejected");
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

    private static final class FakeClock implements Clock {
        private long monotonicNanos;
        private Instant wall;

        FakeClock(Instant start) {
            this.wall = start;
        }

        @Override
        public Instant wallInstant() {
            return wall;
        }

        @Override
        public long monotonicNanos() {
            return monotonicNanos;
        }

        void advance(Duration by) {
            monotonicNanos += by.toNanos();
            wall = wall.plus(by);
        }

        void advanceWallOnly(Duration by) {
            wall = wall.plus(by); // monotonic is frozen while the host sleeps
        }
    }

    private static final class RecordingEvents implements EventLog {
        final List<ServiceEvent> written = new ArrayList<>();

        @Override
        public void write(ServiceEvent event) {
            written.add(event);
        }
    }

    private static final class FakeStream implements StreamControl {
        int rebuilds;
        final List<String> resubscribed = new ArrayList<>();
        final List<String> quarantined = new ArrayList<>();
        boolean quarantineRefused; // when true, quarantine() reports a refused unsubscribe

        @Override
        public void rebuild() {
            rebuilds++;
        }

        @Override
        public void resubscribe(String epic) {
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
