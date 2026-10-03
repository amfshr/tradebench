package dev.amfshr.tradebench.marketdata.supervise;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/** Anchors the ruled numbers other tests derive from, so a drift is caught here, not there. */
class TuningTest {

    @Test
    void theRecoveryBudgetIsTheRuledTenMinutes() {
        // Ruled 2026-10-03: a clock, not a count — see the E1 plan amendment.
        assertEquals(Duration.ofMinutes(10), Tuning.playbook().giveUpAfter());
    }

    @Test
    void withGiveUpAfterChangesOnlyTheBudget() {
        Tuning tuned = Tuning.playbook().withGiveUpAfter(Duration.ofDays(1));

        assertEquals(Duration.ofDays(1), tuned.giveUpAfter());
        assertEquals(Tuning.playbook(), tuned.withGiveUpAfter(Duration.ofMinutes(10)),
                "a round trip is the identity — every other field is carried");
    }
}
