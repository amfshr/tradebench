package dev.amfshr.tradebench.marketdata.testutil;

import java.io.IOException;
import java.util.List;

import dev.amfshr.tradebench.ig.error.IgRetryableException;
import dev.amfshr.tradebench.ig.session.IgSession;
import dev.amfshr.tradebench.ig.session.IgSessions;
import dev.amfshr.tradebench.ig.session.IgTokens;

/** Sessions without IG: every login succeeds at once unless {@link #unreachable}, in which case
 * it fails retryably — IG is down. */
public final class FakeSessions implements IgSessions {

    public static final String ACCOUNT = "AB12CE";

    public int logins;
    public int reLogins;
    public boolean unreachable;

    @Override
    public IgSession current() throws IOException {
        refuseIfUnreachable();
        logins++;
        return session("cst-" + logins);
    }

    @Override
    public IgSession afterFailure() throws IOException {
        refuseIfUnreachable();
        reLogins++;
        return session("cst-r" + reLogins);
    }

    private void refuseIfUnreachable() {
        if (unreachable) {
            throw new IgRetryableException("IG unreachable", 503, null);
        }
    }

    private static IgSession session(String cst) {
        return new IgSession(new IgTokens(cst, "xst"), ACCOUNT, "https://fake.lightstreamer",
                List.of());
    }
}
