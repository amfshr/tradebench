package dev.amfshr.tradebench.marketdata.supervise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.amfshr.tradebench.ig.error.IgFatalConfigException;
import dev.amfshr.tradebench.ig.error.IgRetryableException;
import dev.amfshr.tradebench.ig.session.IgSession;
import dev.amfshr.tradebench.ig.session.IgSessions;
import dev.amfshr.tradebench.ig.session.IgTokens;
import dev.amfshr.tradebench.ig.stream.FakeStreamTransport;
import dev.amfshr.tradebench.ig.stream.IgStreamClient;
import dev.amfshr.tradebench.ig.stream.SealedBarUpdate;
import dev.amfshr.tradebench.ig.stream.StreamEvents;
import dev.amfshr.tradebench.ig.stream.StreamTransport;
import dev.amfshr.tradebench.ig.stream.TickUpdate;

class IgStreamControlTest {

    private static final String DAX = "IX.D.DAX.DAILY.IP";
    private static final String FTSE = "IX.D.FTSE.DAILY.IP";
    private static final String DAX_PRICE = "PRICE:Z6CS3E:" + DAX;
    private static final String DAX_CHART = "CHART:" + DAX + ":1MINUTE";
    private static final String FTSE_PRICE = "PRICE:Z6CS3E:" + FTSE;
    private static final String FTSE_CHART = "CHART:" + FTSE + ":1MINUTE";
    private static final Duration BOOT_RETRY = Duration.ofSeconds(30);
    private static final Duration BOOT_BUDGET = Duration.ofMinutes(10);

    private static final class FakeSessions implements IgSessions {
        int currentCalls;
        int afterFailureCalls;
        final Deque<Exception> bootFailures = new ArrayDeque<>(); // thrown by current(), in order
        @Nullable Exception afterFailureFailsWith;
        boolean alwaysUnreachable; // every login attempt fails retryably — IG is down

        @Override
        public IgSession current() throws IOException, InterruptedException {
            currentCalls++;
            if (alwaysUnreachable) {
                throw new IgRetryableException("IG unreachable", 503, null);
            }
            Exception next = bootFailures.poll();
            if (next != null) {
                raise(next);
            }
            return session("boot");
        }

        @Override
        public IgSession afterFailure() throws IOException, InterruptedException {
            afterFailureCalls++;
            if (afterFailureFailsWith != null) {
                raise(afterFailureFailsWith);
            }
            return session("rebuilt");
        }

        private static void raise(Exception failure) throws IOException, InterruptedException {
            if (failure instanceof IOException io) {
                throw io;
            }
            if (failure instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw (RuntimeException) failure;
        }

        private static IgSession session(String cst) {
            return new IgSession(new IgTokens(cst, "xst"), "Z6CS3E",
                    "https://demo-apd.marketdatasystems.com", List.of());
        }
    }

    private static final class RecordingObserver implements StreamObserver {
        final List<String> subscribed = new ArrayList<>();
        final List<String> rejected = new ArrayList<>();
        final List<String> statuses = new ArrayList<>();

        @Override
        public void onStatusChange(int generation, String status) {
            statuses.add(status);
        }

        @Override
        public void onServerError(int generation, int code, String message) {
        }

        @Override
        public void onSubscribed(int generation, String epic, WitnessQuarantine.Kind kind) {
            subscribed.add(epic + "/" + kind);
        }

        @Override
        public void onSubscriptionError(int generation, String epic, WitnessQuarantine.Kind kind, int code, String message) {
            rejected.add(epic + "/" + kind + "/" + code + "/" + message);
        }
    }

    private static final StreamEvents NO_EVENTS = new StreamEvents() {
        @Override
        public void onTick(TickUpdate tick) {
        }

        @Override
        public void onSealedBar(SealedBarUpdate bar) {
        }

        @Override
        public void onMalformed(String itemName) {
        }
    };

    private final FakeStreamTransport fake = new FakeStreamTransport();
    private final FakeSessions sessions = new FakeSessions();
    private final RecordingObserver observer = new RecordingObserver();
    private final List<String> log = new ArrayList<>();
    private final List<Duration> sleeps = new ArrayList<>();
    private long now; // the fake monotonic clock — each fake sleep advances it
    private IgStreamControl control;

    @BeforeEach
    void setUp() {
        control = new IgStreamControl(sessions, new IgStreamClient(fake), NO_EVENTS,
                List.of(DAX, FTSE), log::add, this::sleep, BOOT_RETRY, () -> now, BOOT_BUDGET);
    }

    private void sleep(Duration duration) {
        sleeps.add(duration);
        now += duration.toNanos();
    }

    private void boot() throws Exception {
        control.bind(observer);
        control.start();
    }

    private static List<String> subscribedItems(FakeStreamTransport.FakeConnection connection) {
        return connection.specs.stream().map(spec -> spec.items().get(0)).toList();
    }

    private static List<String> activeItems(FakeStreamTransport.FakeConnection connection) {
        return connection.active.stream()
                .map(handle -> ((FakeStreamTransport.FakeHandle) handle).spec().items().get(0))
                .toList();
    }

    @Test
    void startConnectsAsTheObserverAndSubscribesEveryMarketsPair() throws Exception {
        boot();

        assertEquals(1, fake.connections.size());
        fake.last().listener.onStatusChange("CONNECTED:WS-STREAMING");
        assertEquals(List.of("CONNECTED:WS-STREAMING"), observer.statuses,
                "the Supervisor hears every connection");
        assertEquals("CST-boot|XST-xst", fake.last().password);
        assertEquals(List.of(DAX_PRICE, DAX_CHART, FTSE_PRICE, FTSE_CHART),
                subscribedItems(fake.last()), "the dual pair per market — E1 doctrine");
        assertEquals(1, sessions.currentCalls);
        assertTrue(sleeps.isEmpty(), "a clean boot never waits");
    }

    @Test
    void startRefusesToRunUnbound() {
        assertThrows(IllegalStateException.class, () -> control.start(),
                "an unbound control would stream into the void — fail loud at boot");
        assertTrue(fake.connections.isEmpty());
        assertEquals(0, sessions.currentCalls, "no login spent on a programming error");
    }

    @Test
    void bootRetriesWhileIgIsUnreachableThenConnects() throws Exception {
        sessions.bootFailures.add(new IgRetryableException("503 from IG", 503, null));
        sessions.bootFailures.add(new IOException("dns"));

        boot();

        assertEquals(3, sessions.currentCalls, "two failures, then the login that worked");
        assertEquals(List.of(BOOT_RETRY, BOOT_RETRY), sleeps, "paced between attempts");
        assertEquals(1, fake.connections.size());
        assertEquals(2, log.stream().filter(line -> line.startsWith("boot attempt")).count(),
                "each wait is announced — loud, not silent");
    }

    @Test
    void bootFailsLoudOnARejectedConfiguration() {
        sessions.bootFailures.add(new IgFatalConfigException("invalid API key"));
        control.bind(observer);

        assertThrows(IgFatalConfigException.class, () -> control.start(),
                "a rejected configuration never self-heals — never retry into a lockout");
        assertTrue(sleeps.isEmpty());
        assertTrue(fake.connections.isEmpty());
        assertTrue(log.getLast().startsWith("FATAL"));
    }

    @Test
    @Timeout(5)
    void bootGivesUpAfterItsBudgetWhenIgStaysUnreachable() {
        sessions.alwaysUnreachable = true;
        control.bind(observer);

        assertThrows(IllegalStateException.class, () -> control.start(),
                "a bounded boot: past the budget the restart policy is the outer loop");
        assertEquals(20, sleeps.size(), "ten minutes of 30s waits — exact at the boundary");
        assertEquals(21, sessions.currentCalls, "the attempt that crossed the budget was the last");
        assertTrue(fake.connections.isEmpty());
        assertTrue(log.getLast().startsWith("FATAL"));
    }

    @Test
    void aSupersededConnectionsLateEventsAreDropped() throws Exception {
        boot();
        FakeStreamTransport.FakeConnection old = fake.last();
        control.rebuild();

        old.listener.onStatusChange("DISCONNECTED");             // the farewell, arriving late
        old.stateListeners.get(0).onSubscriptionError(1, "late"); // a dead leg complaining
        fake.last().listener.onStatusChange("CONNECTED:WS-STREAMING");

        assertEquals(List.of("CONNECTED:WS-STREAMING"), observer.statuses,
                "only the live connection reaches the Supervisor — no phantom outage");
        assertTrue(observer.rejected.isEmpty(), "a superseded leg cannot strike the new session");
    }

    @Test
    void eachLegReportsItsOutcomeToTheObserverWithEpicAndKind() throws Exception {
        boot();
        List<StreamTransport.StateListener> legs = fake.last().stateListeners;

        legs.get(0).onSubscribed();                    // DAX price
        legs.get(1).onSubscribed();                    // DAX chart
        legs.get(3).onSubscriptionError(123, "nope");  // FTSE chart

        assertEquals(List.of(DAX + "/PRICE", DAX + "/CHART"), observer.subscribed);
        assertEquals(List.of(FTSE + "/CHART/123/nope"), observer.rejected);
    }

    @Test
    void rebuildClosesReconnectsViaAfterFailureAndResubscribesOnlySurvivors() throws Exception {
        boot();
        control.quarantine(FTSE);

        assertEquals(StreamControl.Outcome.CONNECTED, control.rebuild());

        assertTrue(fake.connections.get(0).closed, "the old stream is torn down first");
        assertEquals(2, fake.connections.size());
        assertEquals(1, sessions.afterFailureCalls, "the §1.2 validate-reuse-before-re-login path");
        assertEquals("CST-rebuilt|XST-xst", fake.last().password,
                "the rebuilt session, not the boot one");
        fake.last().listener.onStatusChange("CONNECTED:WS-STREAMING");
        assertEquals(List.of("CONNECTED:WS-STREAMING"), observer.statuses,
                "the same observer hears the new connection");
        assertEquals(List.of(DAX_PRICE, DAX_CHART), subscribedItems(fake.last()),
                "the quarantined market stays off");
    }

    @Test
    void resubscribeReplacesJustThatMarketsPairInPlace() throws Exception {
        boot();
        FakeStreamTransport.FakeConnection connection = fake.last();
        StreamTransport.SubscriptionHandle oldDaxPrice = connection.active.get(0);

        control.resubscribe(DAX);

        assertEquals(1, fake.connections.size(), "surgical: no reconnect");
        assertFalse(connection.closed);
        assertFalse(connection.active.contains(oldDaxPrice), "the old DAX legs are gone");
        assertEquals(List.of(FTSE_PRICE, FTSE_CHART, DAX_PRICE, DAX_CHART), activeItems(connection),
                "FTSE untouched; DAX re-subscribed fresh");
    }

    @Test
    void quarantineUnsubscribesThePairAndKeepsItOutOfLaterRebuilds() throws Exception {
        boot();

        assertTrue(control.quarantine(FTSE));

        assertEquals(List.of(DAX_PRICE, DAX_CHART), activeItems(fake.last()));
        control.rebuild();
        assertEquals(List.of(DAX_PRICE, DAX_CHART), subscribedItems(fake.last()));
    }

    @Test
    void aRefusedUnsubscribeIsReportedButTheMarketStaysExcluded() throws Exception {
        boot();
        fake.refuseUnsubscribe = true;

        assertFalse(control.quarantine(FTSE),
                "a refusal double-delivers — the Supervisor must rebuild");
        assertTrue(log.getLast().contains("refused"), "loud, not silent");

        fake.refuseUnsubscribe = false;
        control.rebuild();
        assertEquals(List.of(DAX_PRICE, DAX_CHART), subscribedItems(fake.last()),
                "quarantine persists through the forced rebuild (exit is restart-only)");
    }

    @Test
    void aFailedReloginDuringRebuildIsSwallowedLoudAndLeavesTheStreamDown() throws Exception {
        boot();
        sessions.afterFailureFailsWith = new IOException("IG unreachable");

        assertEquals(StreamControl.Outcome.FAILED, control.rebuild(), "transient — the ladder continues; the sweep thread survives");

        assertTrue(fake.connections.get(0).closed);
        assertEquals(1, fake.connections.size(), "no new connection — the stream is left down");
        assertTrue(log.getLast().contains("rebuild failed"), "loud, not silent");
        control.resubscribe(DAX); // a remedy against a down stream must not throw either
        assertTrue(log.getLast().contains("no live stream"));
    }

    @Test
    void aRefusedConnectDuringRebuildIsSwallowedLoud() throws Exception {
        boot();
        fake.failNextConnect = true;

        assertEquals(StreamControl.Outcome.FAILED, control.rebuild());

        assertEquals(1, fake.connections.size());
        assertTrue(log.getLast().contains("rebuild failed"));
    }

    @Test
    void aRejectedConfigurationDuringRebuildStopsTheLadder() throws Exception {
        boot();
        sessions.afterFailureFailsWith = new IgFatalConfigException("account disabled");

        assertEquals(StreamControl.Outcome.FATAL, control.rebuild(), "FATAL tells the Supervisor to give up at once");

        assertTrue(fake.connections.get(0).closed);
        assertEquals(1, fake.connections.size());
        assertTrue(log.getLast().startsWith("FATAL"));
    }

    @Test
    void anInterruptedReloginReinterruptsTheThreadAndReturns() throws Exception {
        boot();
        sessions.afterFailureFailsWith = new InterruptedException("shutdown");

        StreamControl.Outcome outcome = control.rebuild();

        assertTrue(Thread.interrupted(), "the interrupt is preserved for the sweep loop to honour");
        assertEquals(StreamControl.Outcome.FAILED, outcome, "an interrupt is not a rejected configuration");
        assertEquals(1, fake.connections.size());
    }

    @Test
    void aRefusedUnsubscribeDuringResubscribeOrphansNothingAndNeverDoubleSubscribes() throws Exception {
        boot();
        FakeStreamTransport.FakeConnection connection = fake.last();
        fake.refuseUnsubscribe = true;

        control.resubscribe(DAX); // refused — logged; the old pair stays known

        assertEquals(4, connection.active.size(), "nothing dropped, nothing added — no half-dropped pair");
        assertTrue(log.getLast().contains("resubscribe " + DAX + " failed"));

        fake.refuseUnsubscribe = false;
        control.resubscribe(DAX); // the next attempt still holds the old handles
        assertEquals(List.of(FTSE_PRICE, FTSE_CHART, DAX_PRICE, DAX_CHART), activeItems(connection));
        assertEquals(4, connection.active.size(), "the old DAX pair is gone; exactly one fresh pair");
    }

    @Test
    void quarantineWhileTheStreamIsDownIsAcceptedAndStillExcludesTheMarketLater() throws Exception {
        boot();
        fake.failNextConnect = true;
        assertEquals(StreamControl.Outcome.FAILED, control.rebuild(), "transient — the stream is left down for the ladder");

        assertTrue(control.quarantine(FTSE), "nothing is subscribed while down — nothing to refuse");

        control.rebuild();
        assertEquals(List.of(DAX_PRICE, DAX_CHART), subscribedItems(fake.last()));
    }

    @Test
    void resubscribeRefusesAQuarantinedMarket() throws Exception {
        boot();
        assertTrue(control.quarantine(FTSE));

        control.resubscribe(FTSE); // a Retry that should never have been issued — the second lock

        assertEquals(List.of(DAX_PRICE, DAX_CHART), activeItems(fake.last()), "nothing re-subscribed");
        assertTrue(log.getLast().contains("refused"));
    }

    @Test
    void aRefusedLegIsRetriedAloneNextTimeNeverItsAlreadyDroppedTwin() throws Exception {
        // E1-T10 #15: the SDK refuses one leg's unsubscribe. The leg that did go is forgotten; the
        // refused one is kept for the next attempt, which never re-passes the gone twin (the SDK
        // throws for an inactive subscription) and so completes.
        boot();
        fake.refuseUnsubscribeOf.add(DAX_CHART);
        control.resubscribe(DAX);
        assertEquals(List.of(DAX_CHART, FTSE_PRICE, FTSE_CHART), activeItems(fake.last()),
                "the PRICE leg went, the CHART leg stayed — and nothing new was subscribed over it");
        assertTrue(log.stream().anyMatch(line -> line.startsWith("resubscribe " + DAX + " failed")));

        fake.refuseUnsubscribeOf.clear();
        control.resubscribe(DAX);

        assertEquals(List.of(FTSE_PRICE, FTSE_CHART, DAX_PRICE, DAX_CHART), activeItems(fake.last()),
                "the held leg dropped, a fresh pair up — one of each");
    }

    @Test
    void aFailedSecondLegRollsBackTheFirstSoNothingStreamsUnowned() throws Exception {
        // E1-T10 #16: the CHART subscribe throws after PRICE went up. The PRICE leg is unsubscribed
        // again, the market has no pair, and the next attempt starts clean — never a second PRICE
        // delivering every tick twice.
        boot();
        fake.failSubscribeOf.add(DAX_CHART);
        control.resubscribe(DAX);
        assertEquals(List.of(FTSE_PRICE, FTSE_CHART), activeItems(fake.last()), "rolled back: no DAX leg streams");

        fake.failSubscribeOf.clear();
        control.resubscribe(DAX);

        assertEquals(List.of(FTSE_PRICE, FTSE_CHART, DAX_PRICE, DAX_CHART), activeItems(fake.last()),
                "exactly one pair");
    }

    @Test
    void aSubscribeFailureAtBootFailsLoudAndLeavesNoHalfBuiltStream() {
        // E1-T10 #17: a runtime failure while subscribing at boot is not retried like an outage —
        // it fails loud, and the connection it half-built is closed rather than left streaming.
        fake.failSubscribeOf.add(FTSE_CHART);
        control.bind(observer);

        assertThrows(IllegalStateException.class, control::start);

        assertTrue(fake.last().closed, "the half-built connection is closed");
        assertTrue(log.stream().anyMatch(line -> line.startsWith("FATAL: the stream could not be set up at boot")));
    }

    @Test
    void aSubscribeFailureDuringRebuildLeavesTheStreamDownNotHalfBuilt() throws Exception {
        boot();
        fake.failSubscribeOf.add(DAX_CHART);

        assertEquals(StreamControl.Outcome.FAILED, control.rebuild());

        assertTrue(fake.last().closed, "the half-built connection is closed, not published");
        control.resubscribe(DAX); // no live stream: skipped, never a half-built one subscribed into
        assertTrue(log.stream().anyMatch(line -> line.contains("skipped — no live stream")));
        fake.failSubscribeOf.clear();
        assertEquals(StreamControl.Outcome.CONNECTED, control.rebuild());
        assertEquals(List.of(DAX_PRICE, DAX_CHART, FTSE_PRICE, FTSE_CHART), activeItems(fake.last()));
    }
}
