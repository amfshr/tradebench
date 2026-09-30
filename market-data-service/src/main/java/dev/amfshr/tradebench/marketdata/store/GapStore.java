package dev.amfshr.tradebench.marketdata.store;

import dev.amfshr.tradebench.marketdata.coverage.GapDetector;

/** Persists detected bar gaps (bar_gaps). Idempotent by span — a re-detected gap is a no-op. */
public interface GapStore {

    void record(GapDetector.Gap gap);
}
