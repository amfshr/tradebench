package dev.amfshr.tradebench.marketdata.supervise;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/**
 * Rebuild pacing (§3.2): {@code base·2^(n-1)}, jittered ±50%, capped — then the floor,
 * applied on EVERY rebuild, because rapid re-login storms trip IG's response cache and
 * throttles. Nothing retries forever: {@link #exhausted} stops the service cleanly.
 */
public final class BackoffPolicy {

    private final Tuning tuning;
    private final DoubleSupplier jitter;

    /** {@code jitter} supplies values in [0.5, 1.5) — injectable for exact tests. */
    public BackoffPolicy(Tuning tuning, DoubleSupplier jitter) {
        this.tuning = tuning;
        this.jitter = jitter;
    }

    public Duration delayFor(int attempt) {
        double raw = tuning.backoffBase().toNanos() * Math.pow(2, attempt - 1);
        double jittered = raw * jitter.getAsDouble();
        long capped = (long) Math.min(tuning.backoffCap().toNanos(), jittered);
        return Duration.ofNanos(Math.max(tuning.rebuildFloor().toNanos(), capped));
    }

    public boolean exhausted(int consecutiveFailures) {
        return consecutiveFailures >= tuning.maxConsecutiveFailures();
    }
}
