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
 * <p>The published per-account non-trading limit is 30/min (§6) — the binding one, shared across
 * every service on the account (the per-key limit is 60/min) — but a service starts at
 * {@link #CONSERVATIVE_START} and lets post-login discovery set the real figure.
 */
public final class RequestPacer {

    public static final int ACCOUNT_NON_TRADING_PER_MINUTE = 30;
    /** Where a service starts until discovery reads the real budget: field evidence shows demo keys
     * enforce 10/min, not the published 30 — never assume the constant (E1 plan T5 §). */
    public static final int CONSERVATIVE_START = 10;

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private int permitsPerWindow;
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

    /** Post-login discovery sets the real budget. The window keeps its grants, so a lower budget
     * bites on the very next {@code acquire()} and a raise too takes effect on the next acquire — a waiter already
     * sleeping finishes on the old budget. */
    public synchronized void setPerMinute(int permitsPerWindow) {
        if (permitsPerWindow < 1) {
            throw new IllegalArgumentException("permitsPerWindow must be >= 1");
        }
        this.permitsPerWindow = permitsPerWindow;
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
