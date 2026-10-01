package dev.amfshr.tradebench.marketdata.supervise;

import org.jspecify.annotations.Nullable;

/**
 * The 2h56m scar (§3.3): a client hanging in a {@code DISCONNECTED:*} substate trips no
 * disconnect handler and the SDK has given up. Measure time-in-substate on the monotonic
 * clock and force a rebuild past the threshold — sooner for WILL-RETRY (server-side
 * recovery abandoned; nothing to replay) than TRYING-RECOVERY (lossless replay possible).
 */
public final class StuckSubstateEscalator {

    public static final String WILL_RETRY = "DISCONNECTED:WILL-RETRY";
    public static final String TRYING_RECOVERY = "DISCONNECTED:TRYING-RECOVERY";

    private final Tuning tuning;
    private @Nullable String substate;
    private long enteredNanos;

    public StuckSubstateEscalator(Tuning tuning) {
        this.tuning = tuning;
    }

    public void onStatus(String status, long monotonicNanos) {
        if (WILL_RETRY.equals(status) || TRYING_RECOVERY.equals(status)) {
            if (!status.equals(substate)) {
                substate = status;
                enteredNanos = monotonicNanos;
            }
        } else {
            substate = null;
        }
    }

    /** True = the connection is stuck; the supervisor must rebuild the whole session. */
    public boolean rebuildDue(long monotonicNanos) {
        if (substate == null) {
            return false;
        }
        long threshold = WILL_RETRY.equals(substate)
                ? tuning.willRetryRebuild().toNanos()
                : tuning.tryingRecoveryRebuild().toNanos();
        return monotonicNanos - enteredNanos >= threshold;
    }
}
