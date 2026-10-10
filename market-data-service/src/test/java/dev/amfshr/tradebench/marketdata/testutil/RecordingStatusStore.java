package dev.amfshr.tradebench.marketdata.testutil;

import java.util.ArrayList;
import java.util.List;

import dev.amfshr.tradebench.marketdata.events.CaptureStatus;
import dev.amfshr.tradebench.marketdata.store.StatusStore;

/** Every {@code capture_status} row in order, publish by publish; {@link #down} makes each publish
 * fail (Tier 2). */
public final class RecordingStatusStore implements StatusStore {

    public final List<CaptureStatus> rows = new ArrayList<>();
    public boolean down;

    @Override
    public void upsert(List<CaptureStatus> batch) {
        if (down) {
            throw new IllegalStateException("capture_status unavailable");
        }
        rows.addAll(batch);
    }
}
