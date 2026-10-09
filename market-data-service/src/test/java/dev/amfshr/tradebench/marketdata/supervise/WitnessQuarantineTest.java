package dev.amfshr.tradebench.marketdata.supervise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.marketdata.supervise.WitnessQuarantine.Judgment;
import dev.amfshr.tradebench.marketdata.supervise.WitnessQuarantine.Kind;

class WitnessQuarantineTest {

    private static final String DAX = "IX.D.DAX.DAILY.IP";
    private static final String NDX = "IX.D.NASDAQ.CASH.IP";
    private static final long S = 1_000_000_000L;

    private WitnessQuarantine quarantine;

    @BeforeEach
    void setUp() {
        quarantine = new WitnessQuarantine(Tuning.playbook());
    }

    private void healthyWitness(String epic) {
        quarantine.onSubscribeStarted(epic, 0);
        quarantine.onSubscribed(epic, Kind.PRICE);
        quarantine.onSubscribed(epic, Kind.CHART);
    }

    /** A rejection at {@code t} that earns a surgical retry — and the retry's fresh pair, as the
     * shell starts it. */
    private void retried(String epic, long t) {
        assertEquals(new Judgment.Retry(epic), quarantine.onSubscriptionError(epic, t));
        quarantine.onSubscribeStarted(epic, t);
    }

    @Test
    void firstTwoStrikesAreSurgicalRetries() {
        healthyWitness(NDX);
        quarantine.onSubscribeStarted(DAX, 0);
        retried(DAX, 1 * S);
        retried(DAX, 2 * S);
    }

    @Test
    void thirdStrikeWithAHealthyWitnessQuarantinesJustThatMarket() {
        healthyWitness(NDX);
        quarantine.onSubscribeStarted(DAX, 0);
        retried(DAX, 1 * S);
        retried(DAX, 2 * S);
        assertEquals(new Judgment.Quarantine(DAX), quarantine.onSubscriptionError(DAX, 3 * S),
                "the epic is the only differing variable — market-shaped");
        assertEquals(Set.of(DAX), quarantine.quarantined());
    }

    @Test
    void noWitnessMeansSessionShapedRebuild() {
        quarantine.onSubscribeStarted(DAX, 0);
        retried(DAX, 1 * S);
        retried(DAX, 2 * S);
        assertEquals(new Judgment.Rebuild(DAX), quarantine.onSubscriptionError(DAX, 3 * S),
                "N=1 parity is structural: the last standing market always dies loud");
    }

    @Test
    void bothLegsOfOneAttemptAreOneStrike() {
        // E1-T10 #8: a bad epic has both legs refused per attempt. The second leg is not a second
        // strike, and a straggler from a pair already replaced is not a strike at all.
        healthyWitness(NDX);
        quarantine.onSubscribeStarted(DAX, 0);
        assertEquals(new Judgment.Retry(DAX), quarantine.onSubscriptionError(DAX, 1 * S));
        assertEquals(new Judgment.Ignored(DAX), quarantine.onSubscriptionError(DAX, 1 * S), "the pair's other leg");
        quarantine.onSubscribeStarted(DAX, 2 * S); // the retry's fresh pair
        assertEquals(new Judgment.Ignored(DAX), quarantine.onSubscriptionError(DAX, 1 * S + 1),
                "a leg of the replaced pair, arriving late");
        assertEquals(new Judgment.Retry(DAX), quarantine.onSubscriptionError(DAX, 3 * S), "the second attempt: strike two");
        assertEquals(new Judgment.Ignored(DAX), quarantine.onSubscriptionError(DAX, 3 * S));
        quarantine.onSubscribeStarted(DAX, 4 * S);
        assertEquals(new Judgment.Quarantine(DAX), quarantine.onSubscriptionError(DAX, 5 * S), "the third attempt: the verdict");
    }

    @Test
    void aRebuiltPairsFirstRejectionCountsEvenWhenStampedBeforeTheShellReadItsClock() {
        // The shell stamps a rebuilt session's attempts after the subscribes went out (a paced
        // re-login blocks first); a rejection landing in that gap is the attempt's first, not a
        // straggler — the old session's legs are gated out before the new one exists.
        healthyWitness(NDX);
        quarantine.onSessionRebuilt();
        quarantine.onSubscribeStarted(NDX, 10 * S);
        quarantine.onSubscribeStarted(DAX, 10 * S);
        assertEquals(new Judgment.Retry(DAX), quarantine.onSubscriptionError(DAX, 10 * S - 1), "counted: strike one");
    }

    @Test
    void aWouldBeWitnessInsideItsConfirmWindowMeansWait() {
        quarantine.onSubscribeStarted(NDX, 0);   // subscribing, unconfirmed
        quarantine.onSubscribeStarted(DAX, 0);
        retried(DAX, 1 * S);
        retried(DAX, 2 * S);
        assertInstanceOf(Judgment.Wait.class, quarantine.onSubscriptionError(DAX, 3 * S),
                "don't race a fast rejection into a whole-service rebuild");
        assertTrue(quarantine.quarantined().isEmpty());
    }

    @Test
    void aHeldVerdictIsRenderedWhenItsWitnessConfirms() {
        // E1-T10 #4: the strike stands while the verdict waits; the witness confirming renders it
        // at once — market-shaped, since the witness proves the session fine.
        quarantine.onSubscribeStarted(NDX, 0);
        quarantine.onSubscribeStarted(DAX, 0);
        retried(DAX, 1 * S);
        retried(DAX, 2 * S);
        assertInstanceOf(Judgment.Wait.class, quarantine.onSubscriptionError(DAX, 3 * S));
        assertEquals(List.of(), quarantine.rejudge(4 * S), "still waiting");
        quarantine.onSubscribed(NDX, Kind.PRICE);
        quarantine.onSubscribed(NDX, Kind.CHART);
        assertEquals(List.of(new Judgment.Quarantine(DAX)), quarantine.rejudge(5 * S));
        assertEquals(Set.of(DAX), quarantine.quarantined());
        assertEquals(List.of(), quarantine.rejudge(6 * S), "rendered once");
    }

    @Test
    void aHeldVerdictIsSessionShapedWhenTheWindowLapsesUnconfirmed() {
        quarantine.onSubscribeStarted(NDX, 0);
        quarantine.onSubscribeStarted(DAX, 0);
        retried(DAX, 1 * S);
        retried(DAX, 2 * S);
        assertInstanceOf(Judgment.Wait.class, quarantine.onSubscriptionError(DAX, 3 * S));
        assertEquals(List.of(), quarantine.rejudge(29 * S), "one second inside the witness's window");
        assertEquals(List.of(new Judgment.Rebuild(DAX)), quarantine.rejudge(30 * S), "exactly the window: no witness");
        assertEquals(List.of(), quarantine.rejudge(31 * S), "rendered once");
    }

    @Test
    void refusedUnsubscribeAlwaysRebuilds() {
        assertEquals(new Judgment.Rebuild(DAX), quarantine.onUnsubscribeRefused(DAX),
                "a dangling subscription would double-deliver every update");
    }

    @Test
    void quarantinePersistsAcrossRebuildsButStrikesReset() {
        healthyWitness(NDX);
        quarantine.onSubscribeStarted(DAX, 0);
        retried(DAX, 1 * S);
        retried(DAX, 2 * S);
        quarantine.onSubscriptionError(DAX, 3 * S);
        quarantine.onSessionRebuilt();
        assertEquals(Set.of(DAX), quarantine.quarantined(),
                "config-shaped failures don't self-heal — exit is restart-only");
        quarantine.onSubscribeStarted(NDX, 100 * S);
        assertEquals(new Judgment.Retry(NDX), quarantine.onSubscriptionError(NDX, 101 * S),
                "strikes are per-session");
    }

    @Test
    void aRejectionAfterQuarantineIsIgnoredNotReadmitted() {
        healthyWitness(NDX);
        quarantine.onSubscribeStarted(DAX, 0);
        retried(DAX, 1 * S);
        retried(DAX, 2 * S);
        quarantine.onSubscriptionError(DAX, 3 * S);

        // the pair's other leg is rejected too — its error was already queued when the verdict fell
        assertEquals(new Judgment.Ignored(DAX), quarantine.onSubscriptionError(DAX, 4 * S),
                "quarantined means ignored — never a fresh strike, never re-admitted");
        assertEquals(new Judgment.Ignored(DAX), quarantine.onSubscriptionError(DAX, 100 * S),
                "however late");
        assertEquals(Set.of(DAX), quarantine.quarantined());
    }
}
