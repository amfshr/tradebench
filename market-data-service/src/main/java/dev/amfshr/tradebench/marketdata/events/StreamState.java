package dev.amfshr.tradebench.marketdata.events;

import java.util.Locale;

/** The per-market streaming state the console's health view shows (capture_status.stream_state). */
public enum StreamState {
    CONNECTED_STREAMING,
    RECONNECTING,
    WINDOW_CLOSED,
    QUARANTINED;

    /** The lowercase token stored in {@code capture_status.stream_state}. */
    public String db() {
        return name().toLowerCase(Locale.ROOT);
    }
}
