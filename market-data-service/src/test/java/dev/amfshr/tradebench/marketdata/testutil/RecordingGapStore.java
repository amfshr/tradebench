package dev.amfshr.tradebench.marketdata.testutil;

import java.util.ArrayList;
import java.util.List;

import dev.amfshr.tradebench.marketdata.coverage.GapDetector;
import dev.amfshr.tradebench.marketdata.store.GapStore;

/** Every gap recorded, in order; {@link #down} makes each write fail (Tier 2). */
public final class RecordingGapStore implements GapStore {

    public final List<GapDetector.Gap> gaps = new ArrayList<>();
    public boolean down;

    @Override
    public void record(GapDetector.Gap gap) {
        if (down) {
            throw new IllegalStateException("bar_gaps unavailable");
        }
        gaps.add(gap);
    }
}
