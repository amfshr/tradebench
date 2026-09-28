package dev.amfshr.tradebench.ig.stream;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/** One sealed (CONS_END=1) 1-minute CHART bar; partial candle updates never reach here. */
public record SealedBarUpdate(String epic, Instant startUtc, Ohlc bid, Ohlc offer,
        @Nullable Long lastTradedVolume) {
}
