package dev.amfshr.tradebench.ig.session;

import java.io.IOException;

/**
 * The session seam a stream owner acquires sessions through — {@link IgSessionManager} for
 * real, a fake above it in tests (the same pattern as {@code StreamTransport}). Both paths may
 * log in, so both can fail or be interrupted.
 */
public interface IgSessions {

    /** The current session, logging in fresh only if none exists yet. */
    IgSession current() throws IOException, InterruptedException;

    /** The rebuild path after any failure: reuse the cached session if it still validates,
     * otherwise log in fresh (§1.2 — rapid re-logins receive stale tokens). */
    IgSession afterFailure() throws IOException, InterruptedException;
}
