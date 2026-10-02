package dev.amfshr.tradebench.marketdata.supervise;

import org.jspecify.annotations.Nullable;

/**
 * The per-market freshness the {@link Supervisor} samples each sweep to feed the
 * {@link StalenessWatchdog}. High-frequency tick/bar arrivals are <b>pulled</b> here (a last-seen
 * clock, O(1) to stamp), never pushed onto the control queue — the watchdog only needs the latest.
 * {@code Buffers} implements this in {@code Main} (slice C step 6); the Supervisor's tests fake it.
 */
public interface MarketFreshness {

    /** Monotonic nanos of this market's last tick, or {@link Long#MIN_VALUE} if none seen yet. */
    long lastTickMono(String epic);

    /** Monotonic nanos of this market's last sealed bar, or {@link Long#MIN_VALUE} if none yet. */
    long lastBarMono(String epic);

    /** This market's last-seen DLG_FLAG (market state), or null if none seen yet. */
    @Nullable String dealFlag(String epic);
}
