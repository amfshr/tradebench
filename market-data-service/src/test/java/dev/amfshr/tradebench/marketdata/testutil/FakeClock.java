package dev.amfshr.tradebench.marketdata.testutil;

import java.time.Duration;
import java.time.Instant;

import dev.amfshr.tradebench.core.time.Clock;

/**
 * The one fake {@link Clock} for this module's tests: monotonic and wall advance together, or the
 * wall alone (the host slept — chapter 7's discriminator), and nothing moves unless a test moves
 * it. Replaces the hand-rolled copies the belt's tests grew (E1-T11 / T10 #27).
 */
public final class FakeClock implements Clock {

    public static final Instant DEFAULT_START = Instant.parse("2026-09-28T09:00:00Z");

    private long monotonicNanos;
    private Instant wall;

    public FakeClock() {
        this(DEFAULT_START);
    }

    public FakeClock(Instant start) {
        this.wall = start;
    }

    @Override
    public Instant wallInstant() {
        return wall;
    }

    @Override
    public long monotonicNanos() {
        return monotonicNanos;
    }

    /** Time passes: both clocks move. */
    public void advance(Duration by) {
        monotonicNanos += by.toNanos();
        wall = wall.plus(by);
    }

    /** The host slept: the wall clock jumps, the monotonic clock is frozen. */
    public void advanceWallOnly(Duration by) {
        wall = wall.plus(by);
    }

    /** Whole monotonic seconds since the start — handy for offset assertions. */
    public long seconds() {
        return monotonicNanos / 1_000_000_000L;
    }
}
