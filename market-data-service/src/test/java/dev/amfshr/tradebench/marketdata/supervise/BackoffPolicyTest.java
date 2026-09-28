package dev.amfshr.tradebench.marketdata.supervise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class BackoffPolicyTest {

    @Test
    void floorAppliesOnEveryEarlyAttempt() {
        BackoffPolicy policy = new BackoffPolicy(Tuning.playbook(), () -> 1.0);
        assertEquals(Duration.ofSeconds(5), policy.delayFor(1),
                "1s raw but the 5s floor holds — be kind to IG even on the first retry");
        assertEquals(Duration.ofSeconds(5), policy.delayFor(3));
        assertEquals(Duration.ofSeconds(8), policy.delayFor(4));
        assertEquals(Duration.ofSeconds(32), policy.delayFor(6));
    }

    @Test
    void capHoldsEvenAgainstUpwardJitter() {
        BackoffPolicy policy = new BackoffPolicy(Tuning.playbook(), () -> 1.5);
        assertEquals(Duration.ofSeconds(48), policy.delayFor(6));
        assertEquals(Duration.ofSeconds(60), policy.delayFor(7), "64·1.5 capped at 60");
    }

    @Test
    void downwardJitterStillRespectsTheFloor() {
        BackoffPolicy policy = new BackoffPolicy(Tuning.playbook(), () -> 0.5);
        assertEquals(Duration.ofSeconds(5), policy.delayFor(4), "8·0.5 = 4 → floor 5");
    }

    @Test
    void exhaustionIsExactlyTenConsecutiveFailures() {
        BackoffPolicy policy = new BackoffPolicy(Tuning.playbook(), () -> 1.0);
        assertFalse(policy.exhausted(9));
        assertTrue(policy.exhausted(10), "nothing retries forever — clean stop");
    }
}
