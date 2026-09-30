package dev.amfshr.tradebench.marketdata.events;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One audit-log row (service_events v2, D25): what happened, how severe, and where. {@code epic}
 * is the market or null for a global event; {@code correlationId} threads a sequence (a reconnect
 * storm); {@code detail} is the typed structured payload. {@code eventTimeUtc} is the occurrence
 * time (D38) — the recorded-at time is the database's default.
 */
public record ServiceEvent(
        EventType type,
        Severity severity,
        @Nullable String epic,
        Instant eventTimeUtc,
        @Nullable String correlationId,
        @Nullable JsonNode detail) {

    /** An event at the type's default severity, no market, no correlation, no detail. */
    public static ServiceEvent of(EventType type, Instant eventTimeUtc) {
        return new ServiceEvent(type, type.defaultSeverity(), null, eventTimeUtc, null, null);
    }

    public ServiceEvent forEpic(String epic) {
        return new ServiceEvent(type, severity, epic, eventTimeUtc, correlationId, detail);
    }

    public ServiceEvent withDetail(JsonNode detail) {
        return new ServiceEvent(type, severity, epic, eventTimeUtc, correlationId, detail);
    }

    public EventCategory category() {
        return type.category();
    }
}
