package dev.amfshr.tradebench.marketdata.testutil;

import java.time.Duration;

import dev.amfshr.tradebench.ig.time.Sleeper;

/**
 * Sleeping advances the {@link FakeClock} by exactly the request, so a wait is measurable and no
 * test ever really waits; a runaway loop fails the test instead of hanging it. {@link #onSleep}
 * lets a test act mid-wait (stop the pump, enqueue ticks).
 */
public final class FakeSleeper implements Sleeper {

    public static final int RUNAWAY = 100_000;

    private final FakeClock clock;
    public int sleeps;
    /** The guard — a scenario that legitimately sleeps longer (a twelve-hour stand-down) raises it. */
    public int maxSleeps = RUNAWAY;
    public Runnable onSleep = () -> {
    };

    public FakeSleeper(FakeClock clock) {
        this.clock = clock;
    }

    @Override
    public void sleep(Duration duration) {
        if (++sleeps > maxSleeps) {
            throw new IllegalStateException("runaway wait — a stop() or a deadline not honoured?");
        }
        clock.advance(duration);
        onSleep.run();
    }
}
