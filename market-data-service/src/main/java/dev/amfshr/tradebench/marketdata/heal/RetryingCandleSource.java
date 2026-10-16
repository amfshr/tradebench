package dev.amfshr.tradebench.marketdata.heal;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import dev.amfshr.tradebench.ig.error.IgRetryableException;
import dev.amfshr.tradebench.ig.rest.PriceHistory;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

/**
 * IG's history behind Resilience4j's retry (E1-T6 nod ruling 6 — one standard for resilience
 * across every service). Only a transient failure is retried: an I/O error or an IG error the
 * classifier calls retryable. Never the week's historical allowance running out — IG's code for
 * that classifies as retryable (unknown codes do, for the stream's sake), but waiting minutes
 * cannot refill a weekly budget, so it fails at once (the 2026-09-28 post-sweep ruling).
 */
public final class RetryingCandleSource implements CandleSource {

    static final String ALLOWANCE_EXHAUSTED = "exceeded-account-historical-data-allowance";

    /** The call being retried — in production, a dated history request on the current session. */
    @FunctionalInterface
    public interface Fetch {
        PriceHistory fetch(String epic, Instant fromUtc, Instant toUtc) throws Exception;
    }

    private final Fetch fetch;
    private final Retry retry;

    public RetryingCandleSource(Fetch fetch, int maxAttempts, Duration firstWait) {
        this.fetch = fetch;
        this.retry = Retry.of("ig-history", RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(firstWait, 2.0))
                .retryOnException(RetryingCandleSource::isTransient)
                .build());
    }

    @Override
    public PriceHistory minuteCandles(String epic, Instant fromUtc, Instant toUtc) throws Exception {
        return retry.executeCallable(() -> fetch.fetch(epic, fromUtc, toUtc));
    }

    static boolean isTransient(Throwable failure) {
        if (failure instanceof IgRetryableException ig) {
            String code = ig.errorCode();
            return code == null || !code.toLowerCase(Locale.ROOT).contains(ALLOWANCE_EXHAUSTED);
        }
        return failure instanceof IOException;
    }
}
