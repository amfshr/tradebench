package dev.amfshr.tradebench.marketdata.supervise;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The dead-socket script (§3.3): hours stuck in WILL-RETRY, invisible to is_dead. */
class StuckSubstateEscalatorTest {

    private static final long S = 1_000_000_000L;

    private StuckSubstateEscalator escalator;

    @BeforeEach
    void setUp() {
        escalator = new StuckSubstateEscalator(Tuning.playbook());
    }

    @Test
    void willRetryEscalatesAtExactly120Seconds() {
        escalator.onStatus(StuckSubstateEscalator.WILL_RETRY, 0);
        assertFalse(escalator.rebuildDue(119 * S));
        assertTrue(escalator.rebuildDue(120 * S),
                "server-side recovery abandoned — waiting only delays capture");
    }

    @Test
    void tryingRecoveryGetsMorePatience() {
        escalator.onStatus(StuckSubstateEscalator.TRYING_RECOVERY, 0);
        assertFalse(escalator.rebuildDue(299 * S), "lossless replay still possible");
        assertTrue(escalator.rebuildDue(300 * S));
    }

    @Test
    void repeatedSameStatusDoesNotResetTheStopwatch() {
        escalator.onStatus(StuckSubstateEscalator.WILL_RETRY, 0);
        escalator.onStatus(StuckSubstateEscalator.WILL_RETRY, 100 * S);
        assertTrue(escalator.rebuildDue(120 * S),
                "a re-fired identical status must not launder the elapsed time");
    }

    @Test
    void recoveryClearsAndANewEpisodeStartsFresh() {
        escalator.onStatus(StuckSubstateEscalator.WILL_RETRY, 0);
        escalator.onStatus("CONNECTED:WS-STREAMING", 60 * S);
        assertFalse(escalator.rebuildDue(500 * S));
        escalator.onStatus(StuckSubstateEscalator.WILL_RETRY, 600 * S);
        assertFalse(escalator.rebuildDue(700 * S));
        assertTrue(escalator.rebuildDue(720 * S));
    }
    @Test
    void connectingBetweenRetriesDoesNotLaunderTheElapsedTime() {
        // Re-review 2: a stale endpoint cycles WILL-RETRY → CONNECTING (the attempt, failing in
        // ms–4s) → WILL-RETRY … Each CONNECTING used to null the substate and each WILL-RETRY
        // re-entered it with a fresh stopwatch; nothing else watches a connection that is not
        // streaming, so the cycle ran forever. Time-not-streaming accrues across it.
        for (long t = 0; t <= 116; t += 8) {
            escalator.onStatus(StuckSubstateEscalator.WILL_RETRY, t * S);
            escalator.onStatus("CONNECTING", (t + 4) * S);
        }
        assertFalse(escalator.rebuildDue(119 * S));
        assertTrue(escalator.rebuildDue(120 * S), "120s without streaming, whatever the SDK called them");
    }

    @Test
    void aHandshakeThatNeverStreamsIsStuckAtWillRetrysThreshold() {
        // The boot specimen: start() returns as soon as connect is issued; a connection that sits
        // in CONNECTING or STREAM-SENSING was never rebuilt and never declared dead.
        escalator.onStatus("CONNECTING", 0);
        escalator.onStatus("CONNECTED:STREAM-SENSING", 1 * S);
        assertFalse(escalator.rebuildDue(119 * S));
        assertTrue(escalator.rebuildDue(120 * S), "no retry substate seen: the shorter patience");
    }

    @Test
    void theThresholdFollowsTheRetrySubstateMostRecentlySeen() {
        escalator.onStatus(StuckSubstateEscalator.WILL_RETRY, 0);
        escalator.onStatus(StuckSubstateEscalator.TRYING_RECOVERY, 100 * S);
        assertFalse(escalator.rebuildDue(299 * S), "replay became possible: the longer patience, from the first non-streaming status");
        assertTrue(escalator.rebuildDue(300 * S));
    }

    @Test
    void theThresholdShortensWhenTheServerAbandonsRecovery() {
        escalator.onStatus(StuckSubstateEscalator.TRYING_RECOVERY, 0);
        escalator.onStatus(StuckSubstateEscalator.WILL_RETRY, 100 * S);
        assertFalse(escalator.rebuildDue(119 * S));
        assertTrue(escalator.rebuildDue(120 * S), "replay abandoned: the shorter patience, from the first non-streaming status");
    }

    @Test
    void aBareDisconnectedClearsTheStopwatchForTheDeathLatch() {
        escalator.onStatus(StuckSubstateEscalator.WILL_RETRY, 0);
        escalator.onStatus("DISCONNECTED", 60 * S);
        assertFalse(escalator.rebuildDue(500 * S), "the client gave up for good: the Supervisor's latch rebuilds, not the escalator");
    }

    @Test
    void resetForgetsTheSubstateSoANewConnectionReportsItsOwn() {
        escalator.onStatus(StuckSubstateEscalator.WILL_RETRY, 0);
        escalator.reset();
        assertFalse(escalator.rebuildDue(1_000L * 1_000_000_000L), "the connection this belonged to is gone");
    }
}
