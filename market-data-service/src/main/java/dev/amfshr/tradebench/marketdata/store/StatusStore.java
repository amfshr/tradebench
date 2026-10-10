package dev.amfshr.tradebench.marketdata.store;

import java.util.List;

import dev.amfshr.tradebench.marketdata.events.CaptureStatus;

/**
 * UPSERTs the per-(instance, market) health rows (capture_status) — one row per market, latest
 * wins — in one round trip: one connection, one statement, however many markets, so a database
 * that is down costs the heartbeat one connection wait, not one per market (E1-T10 #22).
 */
public interface StatusStore {

    void upsert(List<CaptureStatus> rows);
}
