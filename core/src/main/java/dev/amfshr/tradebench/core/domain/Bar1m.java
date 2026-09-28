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
 *
 * <p><b>Two "whens", two jobs — don't mix them.</b> A bar's <em>identity</em> follows
 * universal convention: it is named, stored, keyed, and charted by {@link #startUtc} —
 * "the 10:39 bar" covers 10:39–10:40, here as everywhere. {@link #dataTime()} (= the seal
 * instant, 10:40) is <em>not</em> a label: it is the causality stamp that orders the event
 * stream, so no consumer sees a bar before live reality would have shown it (D38). Humans
 * and storage use {@code startUtc}; only event ordering uses {@code dataTime}.
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

    public OhlcPrices mid() {
        return new OhlcPrices(
                midOf(bid.open(), ask.open()),
                midOf(bid.high(), ask.high()),
                midOf(bid.low(), ask.low()),
                midOf(bid.close(), ask.close()));
    }

    // Bare divide is deliberate: /2 always terminates, and a rounding mode would let a
    // future non-/2 edit round silently instead of failing loud (D36).
    @SuppressWarnings("BigDecimalMethodWithoutRoundingCalled")
    private static BigDecimal midOf(BigDecimal bidValue, BigDecimal askValue) {
        return bidValue.add(askValue).divide(TWO);
    }
}
