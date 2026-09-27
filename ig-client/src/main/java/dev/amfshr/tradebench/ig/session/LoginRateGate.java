package dev.amfshr.tradebench.ig.session;

import java.time.Duration;
import java.util.function.LongSupplier;

import dev.amfshr.tradebench.ig.time.Sleeper;

/**
 * Enforces the &gt;60s stagger between fresh logins (§1.2): IG serves cached /session
 * responses to rapid repeat logins, and the cached response can carry stale tokens — every
 * follow-up call then fails. Monotonic clock, so a host sleep can't fake elapsed time.
 */
public final class LoginRateGate {

    static final Duration MIN_INTERVAL = Duration.ofSeconds(61);

    private final LongSupplier monotonicNanos;
    private final Sleeper sleeper;
    private long lastLoginNanos;
    private boolean loginSeen;

    public LoginRateGate(LongSupplier monotonicNanos, Sleeper sleeper) {
        this.monotonicNanos = monotonicNanos;
        this.sleeper = sleeper;
    }

    /** Blocks until a fresh login is safe, then records this login's time. */
    public synchronized void awaitLoginTurn() throws InterruptedException {
        long now = monotonicNanos.getAsLong();
        if (loginSeen) {
            long elapsed = now - lastLoginNanos;
            long remaining = MIN_INTERVAL.toNanos() - elapsed;
            if (remaining > 0) {
                sleeper.sleep(Duration.ofNanos(remaining));
                now = monotonicNanos.getAsLong();
            }
        }
        lastLoginNanos = now;
        loginSeen = true;
    }
}
