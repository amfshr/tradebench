package dev.amfshr.tradebench.ig.rest;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

import dev.amfshr.tradebench.ig.time.Sleeper;

/**
 * Sliding-window pacer for IG REST calls. Lives in the client library — not the service — so
 * every caller (service, heal job, operator CLI) inherits pacing; the prototype's backfill
 * tooling got 403'd precisely because pacing lived only in the service (engineering playbook
 * §4.6).
 *
 * <p>Default budget: the per-account non-trading limit of 30/min (§6) — the binding one,
 * because it is shared across every service on the account (the per-key limit is 60/min).
 */
public final class RequestPacer {

    public static final int ACCOUNT_NON_TRADING_PER_MINUTE = 30;

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final int permitsPerWindow;
    private final LongSupplier monotonicNanos;
    private final Sleeper sleeper;
    private final Deque<Long> grantsInWindow = new ArrayDeque<>();

    public RequestPacer(int permitsPerWindow, LongSupplier monotonicNanos, Sleeper sleeper) {
        if (permitsPerWindow < 1) {
            throw new IllegalArgumentException("permitsPerWindow must be >= 1");
        }
        this.permitsPerWindow = permitsPerWindow;
        this.monotonicNanos = monotonicNanos;
        this.sleeper = sleeper;
    }

    /** Blocks until a request slot is free within the sliding one-minute window. */
    public synchronized void acquire() throws InterruptedException {
        long now = monotonicNanos.getAsLong();
        evictExpired(now);
        while (grantsInWindow.size() >= permitsPerWindow) {
            long oldest = grantsInWindow.getFirst();
            long waitNanos = (oldest + WINDOW.toNanos()) - now;
            if (waitNanos > 0) {
                sleeper.sleep(Duration.ofNanos(waitNanos));
            }
            now = monotonicNanos.getAsLong();
            evictExpired(now);
        }
        grantsInWindow.addLast(now);
    }

    private void evictExpired(long now) {
        // A grant expires exactly WINDOW after it was taken: at the boundary it is expired.
        while (!grantsInWindow.isEmpty()
                && now - grantsInWindow.getFirst() >= WINDOW.toNanos()) {
            grantsInWindow.removeFirst();
        }
    }
}
