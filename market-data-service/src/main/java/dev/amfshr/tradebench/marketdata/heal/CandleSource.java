package dev.amfshr.tradebench.marketdata.heal;

import java.time.Instant;

import dev.amfshr.tradebench.ig.rest.PriceHistory;

/** IG's REST minute history for one window, both ends inclusive; the candles' UTC stamps are the key. */
public interface CandleSource {
    PriceHistory minuteCandles(String epic, Instant fromUtc, Instant toUtc) throws Exception;
}
