package dev.amfshr.tradebench.marketdata.events;

import java.util.Locale;

import static dev.amfshr.tradebench.marketdata.events.EventCategory.DATA_LIVENESS;
import static dev.amfshr.tradebench.marketdata.events.EventCategory.DATA_QUALITY;
import static dev.amfshr.tradebench.marketdata.events.EventCategory.HEAL;
import static dev.amfshr.tradebench.marketdata.events.EventCategory.LIFECYCLE;
import static dev.amfshr.tradebench.marketdata.events.EventCategory.RATE_BUDGET;
import static dev.amfshr.tradebench.marketdata.events.EventCategory.RESILIENCE;
import static dev.amfshr.tradebench.marketdata.events.Severity.ERROR;
import static dev.amfshr.tradebench.marketdata.events.Severity.INFO;
import static dev.amfshr.tradebench.marketdata.events.Severity.WARN;

/**
 * The ruled service-event catalogue (D25) — the single source of truth for
 * {@code service_events.event_type}. Each value carries its {@link EventCategory} and a default
 * {@link Severity}; {@link #db()} is the stored token. Producers are wired across E1-T5 slice C
 * and E1-T6; this enum is the vocabulary they draw from.
 */
public enum EventType {
    SERVICE_START(LIFECYCLE, INFO),
    SERVICE_STOP(LIFECYCLE, INFO),
    CONFIG_LOADED(LIFECYCLE, INFO),
    WINDOW_OPEN(LIFECYCLE, INFO),
    WINDOW_CLOSE(LIFECYCLE, INFO),

    RECONNECT(RESILIENCE, WARN),
    STUCK_SUBSTATE_ESCALATED(RESILIENCE, WARN),
    CONNECTION_DEAD(RESILIENCE, WARN),
    SESSION_REFRESHED(RESILIENCE, INFO),
    TRANSPORT_DOWNGRADED(RESILIENCE, WARN),
    /** Every raw Lightstreamer status transition, as the SDK said it — so an outage Tradebench lives
     * through replays with its real connection sequence (E1-T12 ruling 3; D25 amended 2026-10-10). */
    CONNECTION_STATUS(RESILIENCE, INFO),

    WATCHDOG_STALE(DATA_LIVENESS, WARN),
    WATCHDOG_RECOVERED(DATA_LIVENESS, INFO),
    MARKET_QUARANTINED(DATA_LIVENESS, WARN),
    MARKET_RELEASED(DATA_LIVENESS, INFO),
    SUBSCRIPTION_REJECTED(DATA_LIVENESS, WARN),
    MARKET_STATE_CHANGE(DATA_LIVENESS, INFO),
    HOST_SUSPEND(DATA_LIVENESS, WARN),
    FEED_DEAD(DATA_LIVENESS, ERROR),

    BAR_GAP(DATA_QUALITY, WARN),
    BUCKET_VOID(DATA_QUALITY, WARN),
    ORACLE_MISMATCH(DATA_QUALITY, WARN),
    MALFORMED_UPDATE(DATA_QUALITY, WARN),

    DAILY_HEAL(HEAL, INFO),
    BACKFILL(HEAL, INFO),
    PARQUET_ARCHIVED(HEAL, INFO),
    PG_DUMP(HEAL, INFO),
    DIGEST_SENT(HEAL, INFO),
    DIGEST_SUPPRESSED(HEAL, WARN),

    IG_API_ERROR(EventCategory.ERROR, ERROR),
    DB_ERROR(EventCategory.ERROR, ERROR),
    SINK_FAILURE(EventCategory.ERROR, ERROR),

    PACER_DISCOVERED(RATE_BUDGET, INFO),
    ALLOWANCE_LOW(RATE_BUDGET, WARN),
    ALLOWANCE_EXHAUSTED(RATE_BUDGET, WARN);

    private final EventCategory category;
    private final Severity defaultSeverity;

    EventType(EventCategory category, Severity defaultSeverity) {
        this.category = category;
        this.defaultSeverity = defaultSeverity;
    }

    public EventCategory category() {
        return category;
    }

    public Severity defaultSeverity() {
        return defaultSeverity;
    }

    /** The lowercase token stored in {@code service_events.event_type}. */
    public String db() {
        return name().toLowerCase(Locale.ROOT);
    }
}
