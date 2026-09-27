package dev.amfshr.tradebench.ig.testutil;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

import dev.amfshr.tradebench.ig.time.Sleeper;

/** Fake monotonic clock + sleep recorder; sleeping advances the clock. */
public final class FakeTime {

    private long nanos;
    public final List<Duration> sleeps = new ArrayList<>();

    public LongSupplier clock() {
        return () -> nanos;
    }

    public Sleeper sleeper() {
        return duration -> {
            sleeps.add(duration);
            nanos += duration.toNanos();
        };
    }

    public void advance(Duration duration) {
        nanos += duration.toNanos();
    }
}
