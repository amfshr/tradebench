package dev.amfshr.tradebench.marketdata.supervise;

/**
 * What the {@link Supervisor} drives to recover the stream — the one seam between the resilience
 * decision logic and the real session/stream machinery. Faked in the Supervisor's scenario tests;
 * wired to {@code IgSessionManager} + {@code IgStreamSession} in {@code Main} (slice C step 6).
 * Slice-C step 2b adds the per-market {@code resubscribe}/{@code quarantine} remedies.
 */
public interface StreamControl {

    /** The session-shaped remedy: close the stream, re-establish the session, reconnect, and
     * resubscribe the (non-quarantined) markets. Called when recovery must be wholesale. */
    void rebuild();
}
