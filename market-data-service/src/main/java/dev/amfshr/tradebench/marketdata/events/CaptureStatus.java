package dev.amfshr.tradebench.marketdata.events;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * A per-(instance, market) health snapshot (capture_status, D25), UPSERTed every heartbeat. The
 * console reads the latest row; a stale {@code updatedAtUtc} is itself the box-down signal. The
 * heartbeat producer is wired in E1-T5 slice C.
 */
public record CaptureStatus(
        String instance,
        String epic,
        Instant updatedAtUtc,
        StreamState streamState,
        @Nullable String marketState,
        @Nullable Instant lastTickAtUtc,
        @Nullable Instant lastBarAtUtc,
        long ticksTotal,
        long barsTotal,
        long droppedTicks,
        long malformed,
        long reconnectsTotal,
        @Nullable Integer dbPending) {
}
