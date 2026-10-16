package dev.amfshr.tradebench.ig.rest;

import java.time.Duration;

/** IG read a dated request's window at an offset other than the one it was sent at — a stale login
 * offset across a clock change, typically. Carries the offset IG actually used, read off the
 * returned candles' own local and UTC stamps, so the caller can ask again correctly. */
public final class AccountZoneMismatch extends IllegalStateException {

    private final Duration observedOffset;

    public AccountZoneMismatch(String message, Duration observedOffset) {
        super(message);
        this.observedOffset = observedOffset;
    }

    public Duration observedOffset() {
        return observedOffset;
    }
}
