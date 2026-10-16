package dev.amfshr.tradebench.marketdata.heal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.ig.error.IgFatalConfigException;
import dev.amfshr.tradebench.ig.error.IgRetryableException;
import dev.amfshr.tradebench.ig.rest.Allowance;
import dev.amfshr.tradebench.ig.rest.PriceHistory;

/** E1-T6 ③: only a transient failure is retried — never a lockout, never an exhausted week. */
class RetryingCandleSourceTest {

    private static final Instant FROM = Instant.parse("2026-10-15T10:00:00Z");
    private static final PriceHistory SERVED = new PriceHistory(List.of(), new Allowance(9_000, 10_000, 1));
    private final AtomicInteger calls = new AtomicInteger();

    @Test
    void aTransientFailureIsRetriedAndTheAnswerReturned() throws Exception {
        RetryingCandleSource source = source(attempt -> {
            if (attempt < 3) {
                throw attempt == 1 ? new IOException("connection reset") : new IgRetryableException("503", 503, null);
            }
            return SERVED;
        });

        assertSame(SERVED, source.minuteCandles("IX.D.DAX.DAILY.IP", FROM, FROM));
        assertEquals(3, calls.get(), "an I/O error and a retryable IG error, then the answer");
    }

    @Test
    void anExhaustedWeeklyAllowanceFailsAtOnce() {
        IgRetryableException exhausted = new IgRetryableException("allowance", 403,
                "error.public-api.exceeded-account-historical-data-allowance");
        RetryingCandleSource source = source(attempt -> {
            throw exhausted;
        });

        assertSame(exhausted, assertThrows(IgRetryableException.class,
                () -> source.minuteCandles("IX.D.DAX.DAILY.IP", FROM, FROM)));
        assertEquals(1, calls.get(), "waiting minutes cannot refill a weekly budget");
    }

    @Test
    void aFatalConfigurationErrorFailsAtOnce() {
        RetryingCandleSource source = source(attempt -> {
            throw new IgFatalConfigException("bad key", 401, "error.security.api-key-invalid");
        });

        assertThrows(IgFatalConfigException.class, () -> source.minuteCandles("IX.D.DAX.DAILY.IP", FROM, FROM));
        assertEquals(1, calls.get(), "retrying a lockout makes it worse");
    }

    @Test
    void theRetriesAreBounded() {
        RetryingCandleSource source = source(attempt -> {
            throw new IOException("down");
        });

        assertThrows(IOException.class, () -> source.minuteCandles("IX.D.DAX.DAILY.IP", FROM, FROM));
        assertEquals(3, calls.get(), "three attempts, then the phase fails and the runner's backoff takes over");
    }

    @FunctionalInterface
    private interface Script {
        PriceHistory on(int attempt) throws Exception;
    }

    private RetryingCandleSource source(Script script) {
        return new RetryingCandleSource((epic, from, to) -> script.on(calls.incrementAndGet()), 3, Duration.ofMillis(1));
    }
}
