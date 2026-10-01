package dev.amfshr.tradebench.marketdata.supervise;

import java.time.Duration;

import org.jspecify.annotations.Nullable;

/**
 * Answers the operational question after streaming resumes: did we lose data? (§3.6)
 * A WILL-RETRY sighting means the server abandoned lossless recovery — the outage's data
 * is gone and a heal is owed; otherwise the server replayed and there is no gap. Also
 * annotates host sleep (a lid-closed 16 minutes once reported as "0.4s offline") and
 * flags silent transport downgrades.
 */
public final class ReconnectClassifier {

    public record Reconnect(boolean replayed, Duration wallOutage, Duration awakeOutage,
            boolean hostSleptDuring) {
    }

    public enum Note { TRANSPORT_DOWNGRADED, GRACEFUL_CLOSE }

    private static final String STREAMING = "CONNECTED:WS-STREAMING";
    private static final String POLLING = "CONNECTED:HTTP-POLLING";

    private final Tuning tuning;
    private boolean closing;
    private boolean inOutage;
    private boolean sawWillRetry;
    private long outageStartMono;
    private long outageStartWallMillis;

    public ReconnectClassifier(Tuning tuning) {
        this.tuning = tuning;
    }

    /** Set before an intentional close so the farewell DISCONNECTED is hushed (§3.6). */
    public void closing() {
        closing = true;
    }

    public @Nullable Note noteFor(String status) {
        if (POLLING.equals(status)) {
            return Note.TRANSPORT_DOWNGRADED;
        }
        if (closing && status.startsWith("DISCONNECTED")) {
            return Note.GRACEFUL_CLOSE;
        }
        return null;
    }

    /** Returns the outage summary when this status change ends one; else null. */
    public @Nullable Reconnect onStatus(String status, long monotonicNanos, long wallMillis) {
        if (status.startsWith("DISCONNECTED") && !closing) {
            if (!inOutage) {
                inOutage = true;
                sawWillRetry = false;
                outageStartMono = monotonicNanos;
                outageStartWallMillis = wallMillis;
            }
            if (StuckSubstateEscalator.WILL_RETRY.equals(status)) {
                sawWillRetry = true;
            }
            return null;
        }
        if (STREAMING.equals(status) && inOutage) {
            inOutage = false;
            Duration wall = Duration.ofMillis(wallMillis - outageStartWallMillis);
            Duration awake = Duration.ofNanos(monotonicNanos - outageStartMono);
            boolean slept =
                    wall.minus(awake).compareTo(tuning.hostSleepSkew()) > 0;
            return new Reconnect(!sawWillRetry, wall, awake, slept);
        }
        return null;
    }
}
