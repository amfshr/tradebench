package dev.amfshr.tradebench.marketdata.supervise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void firstTwoStrikesAreSurgicalRetries() {
        healthyWitness(NDX);
        quarantine.onSubscribeStarted(DAX, 0);
        assertEquals(new Judgment.Retry(DAX), quarantine.onSubscriptionError(DAX, 1 * S));
        assertEquals(new Judgment.Retry(DAX), quarantine.onSubscriptionError(DAX, 2 * S));
    }

    @Test
    void thirdStrikeWithAHealthyWitnessQuarantinesJustThatMarket() {
        healthyWitness(NDX);
        quarantine.onSubscribeStarted(DAX, 0);
        quarantine.onSubscriptionError(DAX, 1 * S);
        quarantine.onSubscriptionError(DAX, 2 * S);
        assertEquals(new Judgment.Quarantine(DAX), quarantine.onSubscriptionError(DAX, 3 * S),
                "the epic is the only differing variable — market-shaped");
        assertEquals(Set.of(DAX), quarantine.quarantined());
    }

    @Test
    void noWitnessMeansSessionShapedRebuild() {
        quarantine.onSubscribeStarted(DAX, 0);
        quarantine.onSubscriptionError(DAX, 1 * S);
        quarantine.onSubscriptionError(DAX, 2 * S);
        assertInstanceOf(Judgment.Rebuild.class, quarantine.onSubscriptionError(DAX, 3 * S),
                "N=1 parity is structural: the last standing market always dies loud");
    }

    @Test
    void aWouldBeWitnessInsideItsConfirmWindowMeansWait() {
        quarantine.onSubscribeStarted(NDX, 0);   // subscribing, unconfirmed
        quarantine.onSubscribeStarted(DAX, 0);
        quarantine.onSubscriptionError(DAX, 1 * S);
        quarantine.onSubscriptionError(DAX, 2 * S);
        assertInstanceOf(Judgment.Wait.class, quarantine.onSubscriptionError(DAX, 3 * S),
                "don't race a fast rejection into a whole-service rebuild");
        // once the window lapses with the witness never confirming → session-shaped
        assertInstanceOf(Judgment.Rebuild.class, quarantine.onSubscriptionError(DAX, 40 * S));
    }

    @Test
    void refusedUnsubscribeAlwaysRebuilds() {
        assertInstanceOf(Judgment.Rebuild.class, quarantine.onUnsubscribeRefused(DAX),
                "a dangling subscription would double-deliver every update");
    }

    @Test
    void quarantinePersistsAcrossRebuildsButStrikesReset() {
        healthyWitness(NDX);
        quarantine.onSubscribeStarted(DAX, 0);
        for (int i = 1; i <= 3; i++) {
            quarantine.onSubscriptionError(DAX, i * S);
        }
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
        for (int i = 1; i <= 3; i++) {
            quarantine.onSubscriptionError(DAX, i * S);
        }

        // the pair's other leg is rejected too — its error was already queued when the verdict fell
        assertEquals(new Judgment.Ignored(DAX), quarantine.onSubscriptionError(DAX, 4 * S),
                "quarantined means ignored — never a fresh strike, never re-admitted");
        assertEquals(new Judgment.Ignored(DAX), quarantine.onSubscriptionError(DAX, 100 * S),
                "however late");
        assertEquals(Set.of(DAX), quarantine.quarantined());
    }
}
