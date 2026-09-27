package dev.amfshr.tradebench.ig.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.ig.testutil.FakeTime;

/** The >60s login stagger (§1.2): rapid re-logins get IG's cached, stale-token response. */
class LoginRateGateTest {

    @Test
    void firstLoginPassesImmediately() throws InterruptedException {
        FakeTime time = new FakeTime();
        LoginRateGate gate = new LoginRateGate(time.clock(), time.sleeper());
        gate.awaitLoginTurn();
        assertTrue(time.sleeps.isEmpty());
    }

    @Test
    void secondLoginInsideTheWindowSleepsExactlyTheRemainder() throws InterruptedException {
        FakeTime time = new FakeTime();
        LoginRateGate gate = new LoginRateGate(time.clock(), time.sleeper());
        gate.awaitLoginTurn();
        time.advance(Duration.ofSeconds(1));
        gate.awaitLoginTurn();
        assertEquals(Duration.ofSeconds(60), time.sleeps.get(0),
                "61s minimum interval minus 1s elapsed = exactly 60s of waiting");
    }

    @Test
    void loginExactlyAtTheIntervalBoundaryDoesNotSleep() throws InterruptedException {
        FakeTime time = new FakeTime();
        LoginRateGate gate = new LoginRateGate(time.clock(), time.sleeper());
        gate.awaitLoginTurn();
        time.advance(LoginRateGate.MIN_INTERVAL);
        gate.awaitLoginTurn();
        assertTrue(time.sleeps.isEmpty(), "elapsed == MIN_INTERVAL is enough — no wait");
    }
}
