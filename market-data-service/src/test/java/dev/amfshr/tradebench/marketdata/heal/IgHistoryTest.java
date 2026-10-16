package dev.amfshr.tradebench.marketdata.heal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.ig.rest.AccountZoneMismatch;
import dev.amfshr.tradebench.ig.rest.Allowance;
import dev.amfshr.tradebench.ig.rest.PriceHistory;
import dev.amfshr.tradebench.ig.session.IgSession;
import dev.amfshr.tradebench.ig.session.IgTokens;

/** E1-T6, the doctrine review's findings 3 and 8: a stale login offset is corrected from IG's own word, once. */
class IgHistoryTest {

    private static final Instant FROM = Instant.parse("2026-10-26T07:00:00Z");
    private static final PriceHistory SERVED = new PriceHistory(List.of(), new Allowance(9_000, 10_000, 1));
    private final List<Duration> askedAt = new ArrayList<>();
    private final List<Boolean> sessionsAfterFailure = new ArrayList<>();

    @Test
    void aStaleLoginOffsetIsCorrectedFromIgsAnswerAndKept() throws Exception {
        // logged in during BST (+1h); after the clock change IG serves at +0
        IgHistory history = history(Duration.ofHours(1), offset -> {
            if (!offset.isZero()) {
                throw new AccountZoneMismatch("served at another offset", Duration.ZERO);
            }
            return SERVED;
        });

        history.fetch("IX.D.DAX.DAILY.IP", FROM, FROM, false);
        history.fetch("IX.D.DAX.DAILY.IP", FROM, FROM, false);

        assertEquals(List.of(Duration.ofHours(1), Duration.ZERO, Duration.ZERO), askedAt,
                "one wasted request at the stale offset, then IG's own offset for every later one");
    }

    @Test
    void aSecondMismatchIsAFailureNotALoop() {
        IgHistory history = history(Duration.ofHours(1), offset -> {
            throw new AccountZoneMismatch("never matches", offset.plusHours(1));
        });

        assertThrows(AccountZoneMismatch.class, () -> history.fetch("IX.D.DAX.DAILY.IP", FROM, FROM, false));
        assertEquals(2, askedAt.size());
    }

    @Test
    void aRetryTakesTheSessionsFailurePath() throws Exception {
        IgHistory history = history(Duration.ofHours(1), offset -> SERVED);

        history.fetch("IX.D.DAX.DAILY.IP", FROM, FROM, true);

        assertEquals(List.of(true), sessionsAfterFailure, "a dead token is replaced, not reused");
    }

    @Test
    void aSessionWithNoOffsetAndNothingLearnedRefuses() {
        IgHistory history = history(null, offset -> SERVED);

        assertThrows(IllegalStateException.class, () -> history.fetch("IX.D.DAX.DAILY.IP", FROM, FROM, false));
        assertEquals(List.of(), askedAt, "refused before anything was sent");
    }

    @FunctionalInterface
    private interface Answer {
        PriceHistory at(Duration offset);
    }

    private IgHistory history(Duration loginOffset, Answer answer) {
        IgSession session = new IgSession(new IgTokens("c", "x"), "AB12CE", "https://ls.example", List.of(), loginOffset);
        return new IgHistory(afterFailure -> {
            sessionsAfterFailure.add(afterFailure);
            return session;
        }, (s, epic, from, to, offset) -> {
            askedAt.add(offset);
            return answer.at(offset);
        });
    }
}
