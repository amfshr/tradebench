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

    @Test
    void theLadderCeilingAndTheStrikeCountAreTheRuledPlaybookNumbers() {
        // Engineering playbook §8: ten consecutive failures exhaust the ladder; three strikes judge
        // a subscription. SupervisorTest reads these at run time, so they are pinned here.
        assertEquals(10, Tuning.playbook().maxConsecutiveFailures());
        assertEquals(3, Tuning.playbook().subscriptionStrikes());
    }

    @Test
    void theWatchdogScheduleIsTheRuledNinetyTwoTenAndTwoResubscribes() {
        // Playbook §8 / chapters 8 and 10: remedies at T+90s and T+210s, rebuild at T+450s.
        Tuning playbook = Tuning.playbook();
        assertEquals(Duration.ofSeconds(90), playbook.tickSilent());
        assertEquals(Duration.ofSeconds(210), playbook.barSilentWhileTicksFlow());
        assertEquals(Duration.ofSeconds(60), playbook.watchdogGraceBase());
        assertEquals(2, playbook.watchdogMaxResubscribes());
        assertEquals(Duration.ofHours(12), playbook.standDownTeach(), "D29 (1): the stand-down is bounded");
    }
}
