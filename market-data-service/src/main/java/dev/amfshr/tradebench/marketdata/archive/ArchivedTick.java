package dev.amfshr.tradebench.marketdata.archive;

import java.math.BigDecimal;
import java.time.Instant;

/** One tick as archived: the source rides with it, so a file always says where its data came from (P8). */
public record ArchivedTick(Instant tsUtc, BigDecimal bid, BigDecimal ask, String source) {
}
