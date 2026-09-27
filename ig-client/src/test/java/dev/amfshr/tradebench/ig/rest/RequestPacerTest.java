package dev.amfshr.tradebench.ig.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.ig.testutil.FakeTime;

/** IG's per-minute budgets are real (§6); the pacer lives in the library so tooling inherits it. */
class RequestPacerTest {

    @Test
    void requestsWithinTheBudgetPassImmediately() throws InterruptedException {
        FakeTime time = new FakeTime();
        RequestPacer pacer = new RequestPacer(2, time.clock(), time.sleeper());
        pacer.acquire();
        pacer.acquire();
        assertTrue(time.sleeps.isEmpty());
    }

    @Test
    void requestOverTheBudgetWaitsExactlyUntilTheOldestGrantExpires() throws InterruptedException {
        FakeTime time = new FakeTime();
        RequestPacer pacer = new RequestPacer(2, time.clock(), time.sleeper());
        pacer.acquire();
        time.advance(Duration.ofSeconds(10));
        pacer.acquire();
        pacer.acquire();
        assertEquals(Duration.ofSeconds(50), time.sleeps.get(0),
                "third request must wait for the first grant's window edge: 60s - 10s = 50s");
    }

    @Test
    void aGrantExpiresExactlyAtTheWindowBoundary() throws InterruptedException {
        FakeTime time = new FakeTime();
        RequestPacer pacer = new RequestPacer(1, time.clock(), time.sleeper());
        pacer.acquire();
        time.advance(Duration.ofSeconds(60));
        pacer.acquire();
        assertTrue(time.sleeps.isEmpty(), "elapsed == window is expiry — no wait at the boundary");
    }

    @Test
    void slidingWindowKeepsPacingAcrossWindows() throws InterruptedException {
        FakeTime time = new FakeTime();
        RequestPacer pacer = new RequestPacer(1, time.clock(), time.sleeper());
        pacer.acquire();
        pacer.acquire();
        pacer.acquire();
        assertEquals(2, time.sleeps.size());
        assertEquals(Duration.ofSeconds(60), time.sleeps.get(0));
        assertEquals(Duration.ofSeconds(60), time.sleeps.get(1));
    }
}
