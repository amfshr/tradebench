package dev.amfshr.tradebench.marketdata.supervise;

import org.jspecify.annotations.Nullable;

/**
 * The per-market freshness the {@link Supervisor} samples each sweep to feed the
 * {@link StalenessWatchdog}. High-frequency tick/bar arrivals are <b>pulled</b> here (a last-seen
 * clock, O(1) to stamp), never pushed onto the control queue — the watchdog only needs the latest.
 * {@code Buffers} implements it, stamping on the LS callback thread; {@code Main} wires it; the
 * Supervisor's tests fake it.
 */
public interface MarketFreshness {

    /** Monotonic nanos of this market's last tick that was news — never a snapshot's repeat — or
     * {@link Long#MIN_VALUE} if none seen yet. */
    long lastTickMono(String epic);

    /** How many price updates of any kind this market has had, a snapshot's repeat included — the
     * count a re-ask is measured against, so only a later update re-teaches the flag (E1-T15). */
    long updates(String epic);

    /** Monotonic nanos of this market's last sealed bar, or {@link Long#MIN_VALUE} if none yet. */
    long lastBarMono(String epic);

    /** This market's last-seen DLG_FLAG (market state), or null if none seen yet. */
    @Nullable String dealFlag(String epic);
}
