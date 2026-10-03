package dev.amfshr.tradebench.marketdata.supervise;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * Everything the heartbeat reads from the ingest side to publish {@code capture_status} (D25):
 * the watchdog's freshness ({@link MarketFreshness}) plus per-market lifetime counts, data-time
 * last-seen, and the pump's backlog. Pulled on the heartbeat thread; {@code Buffers} implements
 * it, the probe's tests fake it.
 */
public interface MarketTelemetry extends MarketFreshness {

    /** One market's lifetime counts and data-time last-seen — zeros and nulls if never seen. */
    record MarketCounts(long ticks, long bars, long dropped, long malformed,
            @Nullable Instant lastTickAt, @Nullable Instant lastBarAt) {

        public static final MarketCounts NONE = new MarketCounts(0, 0, 0, 0, null, null);
    }

    MarketCounts countsFor(String epic);

    /** Updates queued for the pump and not yet written — bars plus ticks. */
    int pendingWrites();
}
