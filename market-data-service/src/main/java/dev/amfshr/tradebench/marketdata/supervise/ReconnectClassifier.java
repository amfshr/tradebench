package dev.amfshr.tradebench.marketdata.supervise;

import java.time.Duration;

import org.jspecify.annotations.Nullable;

/**
 * Answers the operational question after streaming resumes: did we lose data? (§3.6)
 * A WILL-RETRY sighting means the server abandoned lossless recovery, and our own rebuild
 * tears the connection down — either way the outage's data is gone and a heal is owed;
 * otherwise the server replayed and there is no gap. Also
 * annotates a host suspend (a 16-minute suspend once reported as "0.4s offline") and flags
 * silent transport downgrades. A torn-down connection's farewell never reaches here — the
 * generation gate is the §3.6 hush (E1-T10 #29).
 */
public final class ReconnectClassifier {

    public record Reconnect(boolean replayed, Duration wallOutage, Duration awakeOutage,
            boolean hostSleptDuring) {
    }

    public enum Note { TRANSPORT_DOWNGRADED }

    private static final String POLLING = "CONNECTED:HTTP-POLLING";

    private final Tuning tuning;
    private boolean inOutage;
    private boolean dataGone;
    private long outageStartMono;
    private long outageStartWallMillis;

    public ReconnectClassifier(Tuning tuning) {
        this.tuning = tuning;
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
        return null;
    }

    /** Data flows on every CONNECTED substate except the STREAM-SENSING handshake — so a resume
     * onto a polling fallback ends an outage (the downgrade itself is a {@link Note}); the
     * Supervisor's health view uses the same rule, so the two can never disagree (ruled 2026-10-03). */
    public static boolean isStreaming(String status) {
        return status.startsWith("CONNECTED:") && !status.endsWith("STREAM-SENSING");
    }

    /** The client has given up for good: a bare {@code DISCONNECTED} — never the two retry
     * substates, which the escalator paces (playbook §3.1: "a bare DISCONNECTED is also dead"). The
     * Supervisor rebuilds on it at once, backoff-paced, because nothing else can (E1-T10 #2). */
    public static boolean isTerminal(String status) {
        return "DISCONNECTED".equals(status);
    }

    /** Returns the outage summary when this status change ends one; else null. */
    public @Nullable Reconnect onStatus(String status, long monotonicNanos, long wallMillis) {
        if (status.startsWith("DISCONNECTED")) {
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
        if (isStreaming(status) && inOutage) {
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
