package dev.amfshr.tradebench.ig.rest;

import java.math.BigDecimal;

import org.jspecify.annotations.Nullable;

/**
 * One OHLC corner as IG serves it: bid and ask separately (mid is computed per-field
 * downstream, deliberately — §2.2); {@code lastTraded} is null for markets without it.
 * BigDecimal throughout — exact-arithmetic discipline (triage D36).
 */
public record PricePoint(BigDecimal bid, BigDecimal ask, @Nullable BigDecimal lastTraded) {
}
