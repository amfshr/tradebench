package dev.amfshr.tradebench.marketdata.supervise;

import org.jspecify.annotations.Nullable;

/**
 * The 2h56m scar (§3.3): a client hanging in a {@code DISCONNECTED:*} substate trips no
 * disconnect handler and the SDK has given up. Measure time-not-streaming on the monotonic clock —
 * the D27 one-streaming rule applied here (E1-T13, the re-review's finding 2): the stopwatch
 * starts at the first status that is not streaming and runs across every non-streaming status the
 * connection reports — CONNECTING, the STREAM-SENSING handshake, STALLED, the two retry
 * substates — until one streams. The threshold follows the retry substate most recently seen:
 * sooner for WILL-RETRY (server-side recovery abandoned; nothing to replay) than TRYING-RECOVERY
 * (lossless replay possible); a connection that never reached one — a handshake that hangs, a
 * retry cycle through CONNECTING — gets WILL-RETRY's. A bare DISCONNECTED clears it: the client
 * has given up for good and the Supervisor's death latch owns that path.
 */
public final class StuckSubstateEscalator {

    public static final String WILL_RETRY = "DISCONNECTED:WILL-RETRY";
    public static final String TRYING_RECOVERY = "DISCONNECTED:TRYING-RECOVERY";

    private final Tuning tuning;
    private boolean notStreaming;
    private long sinceNanos;
    private @Nullable String substate; // the retry substate most recently seen: it sets the threshold

    public StuckSubstateEscalator(Tuning tuning) {
        this.tuning = tuning;
    }

    /** The connection this stopwatch belonged to is gone: a new one reports its own (E1-T10 #18). */
    public void reset() {
        notStreaming = false;
        substate = null;
    }

    public void onStatus(String status, long monotonicNanos) {
        if (ReconnectClassifier.isStreaming(status) || ReconnectClassifier.isTerminal(status)) {
            reset();
            return;
        }
        if (!notStreaming) {
            notStreaming = true;
            sinceNanos = monotonicNanos;
        }
        if (WILL_RETRY.equals(status) || TRYING_RECOVERY.equals(status)) {
            substate = status;
        }
    }

    /** True = the connection is stuck; the supervisor must rebuild the whole session. */
    public boolean rebuildDue(long monotonicNanos) {
        if (!notStreaming) {
            return false;
        }
        long threshold = TRYING_RECOVERY.equals(substate)
                ? tuning.tryingRecoveryRebuild().toNanos()
                : tuning.willRetryRebuild().toNanos();
        return monotonicNanos - sinceNanos >= threshold;
    }
}
