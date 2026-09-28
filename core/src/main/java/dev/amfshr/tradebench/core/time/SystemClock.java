package dev.amfshr.tradebench.core.time;

import java.time.Instant;

/** The live implementation — the only place the platform reads real clocks. */
public final class SystemClock implements Clock {

    @Override
    public Instant wallInstant() {
        return Instant.now();
    }

    @Override
    public long monotonicNanos() {
        return System.nanoTime();
    }
}
