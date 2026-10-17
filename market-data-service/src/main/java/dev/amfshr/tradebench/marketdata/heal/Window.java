package dev.amfshr.tradebench.marketdata.heal;

import java.time.Instant;

/** A span the collector was observing, both ends inclusive: from a boot to its last sign of life. */
public record Window(Instant from, Instant to) {

    boolean covers(Instant start, Instant endExclusive) {
        return !from.isAfter(start) && !to.isBefore(endExclusive.minusNanos(1));
    }
}
