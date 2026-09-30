package dev.amfshr.tradebench.marketdata.events;

import java.util.Locale;

/** Event severity — the console's errors/warns filter and the quiet-is-healthy stream (D25). */
public enum Severity {
    INFO,
    WARN,
    ERROR;

    /** The lowercase token stored in {@code service_events.severity}. */
    public String db() {
        return name().toLowerCase(Locale.ROOT);
    }
}
