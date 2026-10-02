package dev.amfshr.tradebench.marketdata.supervise;

/**
 * What the {@link Supervisor} drives to recover the stream — the one seam between the resilience
 * decision logic and the real session/stream machinery. Faked in the Supervisor's scenario tests;
 * wired to {@code IgSessionManager} + {@code IgStreamSession} in {@code Main} (slice C step 6).
 * All three remedies are per-item below the connection, so they compose with multi-user later
 * (backlog B1) without change.
 */
public interface StreamControl {

    /** The session-shaped remedy: close the stream, re-establish the session, reconnect, and
     * resubscribe the (non-quarantined) markets. Called when recovery must be wholesale. */
    void rebuild();

    /** The market-shaped remedy: re-subscribe one market's pair in place, leaving the others
     * (and the connection) untouched — surgical recovery via the increment-1 handles (§3.5). */
    void resubscribe(String epic);

    /** The isolation remedy: unsubscribe one market's pair and leave it off (no resubscribe) — a
     * provably market-shaped failure must not keep wobbling a healthy session (§3.5). Returns
     * {@code false} if the unsubscribe itself was refused, which would double-deliver every update
     * and so forces a whole-session rebuild. */
    boolean quarantine(String epic);
}
