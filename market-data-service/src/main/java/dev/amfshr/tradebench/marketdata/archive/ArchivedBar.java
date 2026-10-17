package dev.amfshr.tradebench.marketdata.archive;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

import dev.amfshr.tradebench.core.domain.OhlcPrices;

/** One 1m bar as archived — streamed or healed, told apart by its source (P8). */
public record ArchivedBar(Instant startUtc, OhlcPrices bid, OhlcPrices ask, @Nullable Long lastTradedVolume,
        String source) {
}
