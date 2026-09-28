package dev.amfshr.tradebench.core.time;

import java.time.Instant;

/**
 * The injectable clock SPI (engineering convention: no business logic reads a wall clock
 * directly). The two times are deliberately separate because their failure modes differ:
 * wall time jumps (NTP, DST, host sleep); monotonic time only moves forward. The
 * sleep/freeze discriminators (playbook §3.3–§3.4) work by comparing the two.
 */
public interface Clock {

    /** Wall-clock now — for timestamps and reporting, never for elapsed-time maths. */
    Instant wallInstant();

    /** Monotonic nanos — for elapsed time, staleness, pacing; meaningless as a date. */
    long monotonicNanos();
}
