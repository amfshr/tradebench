package dev.amfshr.tradebench.core.domain;

import java.math.BigDecimal;

/** One OHLC quadruple. Exact decimals throughout (D36). */
public record OhlcPrices(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
}
