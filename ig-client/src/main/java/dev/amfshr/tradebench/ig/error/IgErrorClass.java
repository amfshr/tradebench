package dev.amfshr.tradebench.ig.error;

/**
 * The IG error taxonomy (playbook §1.6) — ported first because it prevents both classic
 * failure modes: retrying a fatal config error into an IG key lockout, and dying on a
 * transient token expiry.
 */
public enum IgErrorClass {
    /** Retry can never fix it; stop immediately (hammering login risks key revocation). */
    FATAL_CONFIG,
    /** A rebuild/backoff fixes it — token expiry, rate limits, transient server errors. */
    RETRYABLE
}
