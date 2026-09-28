package dev.amfshr.tradebench.ig.stream;

import java.math.BigDecimal;

/** One OHLC quadruple as IG serves it on the CHART stream. */
public record Ohlc(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
}
