package dev.amfshr.tradebench.ig.rest;

import java.time.Instant;

/**
 * One REST v3 candle, keyed on {@code snapshotTimeUTC} — the only timezone-proof field
 * (§4.2). While the market trades, IG's "last N" includes the currently-forming candle;
 * callers fetch N+1 and window-filter (the client never guesses the caller's window).
 */
public record PriceCandle(Instant snapshotTimeUtc, PricePoint open, PricePoint high,
        PricePoint low, PricePoint close, Long lastTradedVolume) {
}
