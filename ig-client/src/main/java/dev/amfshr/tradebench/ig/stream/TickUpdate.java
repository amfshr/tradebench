package dev.amfshr.tradebench.ig.stream;

import java.math.BigDecimal;
import java.time.Instant;

/** One PRICE tick. {@code dealFlag} is the market-state vocabulary (§2.2), wire-stripped. */
public record TickUpdate(String epic, Instant timestampUtc, BigDecimal bid, BigDecimal ask,
        String dealFlag) {
}
