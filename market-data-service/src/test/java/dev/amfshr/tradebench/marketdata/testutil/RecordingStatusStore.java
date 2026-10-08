package dev.amfshr.tradebench.marketdata.testutil;

import java.util.ArrayList;
import java.util.List;

import dev.amfshr.tradebench.marketdata.events.CaptureStatus;
import dev.amfshr.tradebench.marketdata.store.StatusStore;

/** Every {@code capture_status} upsert in order; {@link #down} makes each one fail (Tier 2). */
public final class RecordingStatusStore implements StatusStore {

    public final List<CaptureStatus> rows = new ArrayList<>();
    public boolean down;

    @Override
    public void upsert(CaptureStatus status) {
        if (down) {
            throw new IllegalStateException("capture_status unavailable");
        }
        rows.add(status);
    }
}
