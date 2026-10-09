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
    void resetForgetsTheSubstateSoANewConnectionReportsItsOwn() {
        escalator.onStatus(StuckSubstateEscalator.WILL_RETRY, 0);
        escalator.reset();
        assertFalse(escalator.rebuildDue(1_000L * 1_000_000_000L), "the connection this belonged to is gone");
    }
}
