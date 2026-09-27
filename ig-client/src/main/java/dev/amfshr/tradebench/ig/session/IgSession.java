package dev.amfshr.tradebench.ig.session;

import java.util.List;

/**
 * An authenticated IG session on the configured account: tokens (post-switch), the active
 * account id, the Lightstreamer endpoint for streaming (T3), and the profile's accounts.
 */
public record IgSession(IgTokens tokens, String activeAccountId, String lightstreamerEndpoint,
        List<IgAccount> accounts) {
}
