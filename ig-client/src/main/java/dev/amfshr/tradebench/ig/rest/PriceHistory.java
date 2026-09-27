package dev.amfshr.tradebench.ig.rest;

import java.util.List;

/** A /prices page: the candles plus the allowance state after this request. */
public record PriceHistory(List<PriceCandle> candles, Allowance allowance) {
}
