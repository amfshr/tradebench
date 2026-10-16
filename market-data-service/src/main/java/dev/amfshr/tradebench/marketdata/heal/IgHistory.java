package dev.amfshr.tradebench.marketdata.heal;

import java.time.Duration;
import java.time.Instant;

import org.jspecify.annotations.Nullable;

import dev.amfshr.tradebench.ig.rest.AccountZoneMismatch;
import dev.amfshr.tradebench.ig.rest.PriceHistory;
import dev.amfshr.tradebench.ig.session.IgSession;

/**
 * The heal's dated history requests against IG. Two things the login cannot be trusted with for a
 * whole process life: the account's offset (a clock change makes the login's stale — when IG serves
 * a window at another offset, the offset it used is learned from its own candles and the request
 * asked once more, then kept), and the session itself (a retry takes the failure path, which
 * replaces a dead token rather than reuse it).
 */
public final class IgHistory implements RetryingCandleSource.Fetch {

    /** A session, fresh after a failure when asked. */
    @FunctionalInterface
    public interface Sessions {
        IgSession get(boolean afterFailure) throws Exception;
    }

    /** One dated request at a given offset. */
    @FunctionalInterface
    public interface Dated {
        PriceHistory fetch(IgSession session, String epic, Instant fromUtc, Instant toUtc, Duration offset) throws Exception;
    }

    private final Sessions sessions;
    private final Dated dated;
    private @Nullable Duration learned;

    public IgHistory(Sessions sessions, Dated dated) {
        this.sessions = sessions;
        this.dated = dated;
    }

    @Override
    public PriceHistory fetch(String epic, Instant fromUtc, Instant toUtc, boolean afterFailure) throws Exception {
        IgSession session = sessions.get(afterFailure);
        Duration offset = learned != null ? learned : session.accountUtcOffset();
        if (offset == null) {
            throw new IllegalStateException("the session carries no account timezoneOffset — refusing a dated request");
        }
        try {
            return dated.fetch(session, epic, fromUtc, toUtc, offset);
        } catch (AccountZoneMismatch mismatch) {
            learned = mismatch.observedOffset();
            return dated.fetch(session, epic, fromUtc, toUtc, learned); // a second mismatch is a failure, not a loop
        }
    }
}
