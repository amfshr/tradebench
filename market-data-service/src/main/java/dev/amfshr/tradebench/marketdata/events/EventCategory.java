package dev.amfshr.tradebench.marketdata.events;

import java.util.Locale;

/** The grouping the console filters and rolls up by (D25); each {@link EventType} carries one. */
public enum EventCategory {
    LIFECYCLE,
    RESILIENCE,
    DATA_LIVENESS,
    DATA_QUALITY,
    HEAL,
    ERROR,
    RATE_BUDGET;

    /** The lowercase token stored in {@code service_events.category}. */
    public String db() {
        return name().toLowerCase(Locale.ROOT);
    }
}
