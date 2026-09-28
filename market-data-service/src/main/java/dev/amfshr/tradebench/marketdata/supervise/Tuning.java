package dev.amfshr.tradebench.marketdata.supervise;

import java.time.Duration;

/**
 * Every load-bearing resilience number (playbook §8) in one place. These are policy
 * constants proven against live incidents, not machine config — code defaults are
 * deliberate (T5 nod ruling); env overrides arrive only if we ever actually tune.
 */
public record Tuning(
        Duration tickSilent,
        Duration barSilentWhileTicksFlow,
        Duration willRetryRebuild,
        Duration tryingRecoveryRebuild,
        Duration watchdogGraceBase,
        Duration watchdogGraceCap,
        int watchdogMaxResubscribes,
        Duration backoffBase,
        Duration backoffCap,
        Duration rebuildFloor,
        int maxConsecutiveFailures,
        int subscriptionStrikes,
        Duration subscribeConfirmWindow,
        Duration hostSleepSkew,
        Duration processFreezeJump) {

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
                2,
                Duration.ofSeconds(1),
                Duration.ofSeconds(60),
                Duration.ofSeconds(5),
                10,
                3,
                Duration.ofSeconds(30),
                Duration.ofSeconds(5),
                Duration.ofSeconds(10));
    }
}
