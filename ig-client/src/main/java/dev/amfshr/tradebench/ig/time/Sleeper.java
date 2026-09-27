package dev.amfshr.tradebench.ig.time;

import java.time.Duration;

/**
 * Injectable sleep seam — pacing and login-stagger logic must be unit-testable with fake
 * clocks (engineering convention: injectable clocks everywhere; no real waiting in tests).
 */
public interface Sleeper {

    Sleeper SYSTEM = duration -> Thread.sleep(duration.toMillis());

    void sleep(Duration duration) throws InterruptedException;
}
