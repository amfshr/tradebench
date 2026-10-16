package dev.amfshr.tradebench.ig.session;

import java.time.Duration;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * An authenticated IG session on the configured account: tokens (post-switch), the active
 * account id, the Lightstreamer endpoint for streaming (T3), the profile's accounts, and the
 * account's offset from UTC as IG reports it at login ({@code timezoneOffset}) — the zone IG
 * reads a dated history request's {@code from}/{@code to} in (captured 2026-10-16). Null when
 * the login response carried none; the stream never needs it, a dated request refuses without it.
 */
public record IgSession(IgTokens tokens, String activeAccountId, String lightstreamerEndpoint,
        List<IgAccount> accounts, @Nullable Duration accountUtcOffset) {

    public IgSession(IgTokens tokens, String activeAccountId, String lightstreamerEndpoint,
            List<IgAccount> accounts) {
        this(tokens, activeAccountId, lightstreamerEndpoint, accounts, null);
    }
}
