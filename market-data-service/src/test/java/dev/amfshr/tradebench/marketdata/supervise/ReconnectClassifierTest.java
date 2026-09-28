package dev.amfshr.tradebench.marketdata.supervise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.marketdata.supervise.ReconnectClassifier.Note;
import dev.amfshr.tradebench.marketdata.supervise.ReconnectClassifier.Reconnect;

class ReconnectClassifierTest {

    private static final long S = 1_000_000_000L;

    private ReconnectClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new ReconnectClassifier(Tuning.playbook());
    }

    @Test
    void tryingRecoveryOnlyMeansReplayedNoGap() {
        classifier.onStatus(StuckSubstateEscalator.TRYING_RECOVERY, 0, 0);
        Reconnect summary = classifier.onStatus("CONNECTED:WS-STREAMING", 30 * S, 30_000);
        assertTrue(summary.replayed(), "server replayed the missed updates — no data gap");
        assertEquals(Duration.ofSeconds(30), summary.wallOutage());
    }

    @Test
    void willRetrySightingMeansReplacedDataGone() {
        classifier.onStatus(StuckSubstateEscalator.TRYING_RECOVERY, 0, 0);
        classifier.onStatus(StuckSubstateEscalator.WILL_RETRY, 10 * S, 10_000);
        Reconnect summary = classifier.onStatus("CONNECTED:WS-STREAMING", 30 * S, 30_000);
        assertFalse(summary.replayed(), "the outage's data is gone — a heal is owed");
    }

    @Test
    void hostSleepIsAnnotatedByClockDivergence() {
        classifier.onStatus(StuckSubstateEscalator.WILL_RETRY, 0, 0);
        // the 16-minute lid-close once reported as "0.4s offline": wall 960s, awake 1s
        Reconnect summary = classifier.onStatus("CONNECTED:WS-STREAMING", 1 * S, 960_000);
        assertTrue(summary.hostSleptDuring());
        assertEquals(Duration.ofSeconds(960), summary.wallOutage());
        assertEquals(Duration.ofSeconds(1), summary.awakeOutage());
    }

    @Test
    void transportDowngradeIsFlagged() {
        assertEquals(Note.TRANSPORT_DOWNGRADED, classifier.noteFor("CONNECTED:HTTP-POLLING"));
        assertNull(classifier.noteFor("CONNECTED:WS-STREAMING"));
    }

    @Test
    void gracefulCloseIsHushed() {
        classifier.closing();
        assertEquals(Note.GRACEFUL_CLOSE, classifier.noteFor("DISCONNECTED"));
        assertNull(classifier.onStatus("DISCONNECTED", 5 * S, 5_000),
                "a scheduled stop must not write scare-lines into the warnings stream");
    }
}
