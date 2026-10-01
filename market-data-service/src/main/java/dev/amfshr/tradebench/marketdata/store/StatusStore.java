package dev.amfshr.tradebench.marketdata.store;

import dev.amfshr.tradebench.marketdata.events.CaptureStatus;

/** UPSERTs the per-(instance, market) health row (capture_status) — one row, latest wins. */
public interface StatusStore {

    void upsert(CaptureStatus status);
}
