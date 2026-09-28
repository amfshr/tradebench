package dev.amfshr.tradebench.core.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * One sealed 1-minute bar — the canonical aggregation base (D15): every derived timeframe
 * builds from the healed 1m record, never from ticks. Bid and ask OHLC are stored
 * separately (the finest thing IG serves); {@link #mid()} is the derived per-field mid —
 * computed at 1m and aggregated upward, the oracle-compatible order of operations (D15d).
 */
public record Bar1m(String epic, Instant startUtc, OhlcPrices bid, OhlcPrices ask,
        @Nullable Long tickVolume) implements MarketEvent {

    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    /** The instant the bar sealed — its data time (a bar "happens" when it completes). */
    public Instant sealInstant() {
        return startUtc.plus(ONE_MINUTE);
    }

    @Override
    public Instant dataTime() {
        return sealInstant();
    }

    /**
     * The per-field mid (mid of the opens, mid of the highs, …) — deliberately NOT a true
     * mid-series high/low. This is THE 1m mid that higher timeframes aggregate (D15d);
     * midding after aggregating bid/ask upward gives different highs/lows and would break
     * the T8 shadow-diff against the prototype's proven formula.
     */
    public OhlcPrices mid() {
        return new OhlcPrices(
                midOf(bid.open(), ask.open()),
                midOf(bid.high(), ask.high()),
                midOf(bid.low(), ask.low()),
                midOf(bid.close(), ask.close()));
    }

    private static BigDecimal midOf(BigDecimal bidValue, BigDecimal askValue) {
        // Division by two always terminates in decimal — no rounding mode, stays exact.
        return bidValue.add(askValue).divide(TWO);
    }
}
