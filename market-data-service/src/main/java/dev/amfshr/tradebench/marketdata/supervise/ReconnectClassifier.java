package dev.amfshr.tradebench.marketdata.supervise;

import java.time.Duration;

import org.jspecify.annotations.Nullable;

/**
 * Answers the operational question after streaming resumes: did we lose data? (§3.6)
 * A WILL-RETRY sighting means the server abandoned lossless recovery, and our own rebuild
 * tears the connection down — either way the outage's data is gone and a heal is owed;
 * otherwise the server replayed and there is no gap. Also
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
    private boolean dataGone;
    private long outageStartMono;
    private long outageStartWallMillis;

    public ReconnectClassifier(Tuning tuning) {
        this.tuning = tuning;
    }

    /** Set before an intentional close so the farewell DISCONNECTED is hushed (§3.6). */
    public void closing() {
        closing = true;
    }

    /** The Supervisor is tearing the connection down to rebuild it. The outage is open from here
     * if it was not already, and the eventual STREAMING is a replacement, never a lossless replay
     * — so the ladder reset never depends on the torn-down connection's farewell DISCONNECTED
     * beating the new connection's STREAMING to the queue (status may read CONNECTED throughout a
     * silent-while-connected outage). */
    public void rebuilding(long monotonicNanos, long wallMillis) {
        if (!inOutage) {
            inOutage = true;
            outageStartMono = monotonicNanos;
            outageStartWallMillis = wallMillis;
        }
        dataGone = true; // lossless recovery is off the table — we tore the connection down
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
                dataGone = false;
                outageStartMono = monotonicNanos;
                outageStartWallMillis = wallMillis;
            }
            if (StuckSubstateEscalator.WILL_RETRY.equals(status)) {
                dataGone = true;
            }
            return null;
        }
        if (STREAMING.equals(status) && inOutage) {
            inOutage = false;
            Duration wall = Duration.ofMillis(wallMillis - outageStartWallMillis);
            Duration awake = Duration.ofNanos(monotonicNanos - outageStartMono);
            boolean slept =
                    wall.minus(awake).compareTo(tuning.hostSleepSkew()) > 0;
            return new Reconnect(!dataGone, wall, awake, slept);
        }
        return null;
    }
}
