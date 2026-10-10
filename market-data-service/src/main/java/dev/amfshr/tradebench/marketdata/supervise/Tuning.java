package dev.amfshr.tradebench.marketdata.supervise;

import java.time.Duration;

/**
 * Every load-bearing resilience number (playbook §8) in one place. These are policy
 * constants proven against live incidents, not machine config — code defaults are
 * deliberate (T5 nod ruling); env overrides arrive only if we ever actually tune.
 * {@code giveUpAfter} bounds a whole recovery (ruled 2026-10-03): rung cadence is
 * detector-driven, so the budget is a clock, not a count.
 */
public record Tuning(
        Duration tickSilent,
        Duration barSilentWhileTicksFlow,
        Duration willRetryRebuild,
        Duration tryingRecoveryRebuild,
        Duration watchdogGraceBase,
        Duration watchdogGraceCap,
        /** The session ladder's own cap: how long a session that answers "streaming" and sends
         * nothing is left alone between rebuilds — one login per cap at the limit, a recovery that
         * needs a rebuild caught within it (E1-T12 ruling 4, Alex 2026-10-10). */
        Duration sessionGraceCap,
        int watchdogMaxResubscribes,
        Duration backoffBase,
        Duration backoffCap,
        Duration rebuildFloor,
        int maxConsecutiveFailures,
        Duration giveUpAfter,
        int subscriptionStrikes,
        Duration subscribeConfirmWindow,
        Duration hostSleepSkew,
        Duration processFreezeJump,
        /** How long a market may read CLOSED/SUSPEND before one teaching resubscribe re-learns its
         * flag — the bound on the watchdog's stand-down (D29 (1)). */
        Duration standDownTeach) {

    public Tuning {
        if (willRetryRebuild.compareTo(tryingRecoveryRebuild) > 0) {
            throw new IllegalArgumentException(
                    "invariant will_retry <= trying_recovery violated (§3.3): abandoned"
                            + " recovery must escalate no later than in-progress recovery");
        }
    }

    public static Tuning playbook() {
        return new Tuning(
                Duration.ofSeconds(90),
                Duration.ofSeconds(210),
                Duration.ofSeconds(120),
                Duration.ofSeconds(300),
                Duration.ofSeconds(60),
                Duration.ofMinutes(30),
                Duration.ofMinutes(10),
                2,
                Duration.ofSeconds(1),
                Duration.ofSeconds(60),
                Duration.ofSeconds(5),
                10,
                Duration.ofMinutes(10),
                3,
                Duration.ofSeconds(30),
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                Duration.ofHours(12));
    }

    /** The same policy with a different recovery budget — lets a test pin the rebuild ceiling
     * (or the budget) in isolation. */
    public Tuning withGiveUpAfter(Duration giveUpAfter) {
        return new Tuning(tickSilent, barSilentWhileTicksFlow, willRetryRebuild,
                tryingRecoveryRebuild, watchdogGraceBase, watchdogGraceCap, sessionGraceCap,
                watchdogMaxResubscribes, backoffBase, backoffCap, rebuildFloor,
                maxConsecutiveFailures, giveUpAfter, subscriptionStrikes, subscribeConfirmWindow,
                hostSleepSkew, processFreezeJump, standDownTeach);
    }
}
