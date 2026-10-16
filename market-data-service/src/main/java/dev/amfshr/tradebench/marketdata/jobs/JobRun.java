package dev.amfshr.tradebench.marketdata.jobs;

import java.time.Instant;

/** One recorded attempt of one phase for one market-day. */
public record JobRun(String job, MarketDay day, Instant startedUtc, Instant finishedUtc, boolean succeeded,
        String detailJson) {
}
