package dev.amfshr.tradebench.ig.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void loweringTheBudgetBitesOnTheNextAcquire() throws InterruptedException {
        FakeTime time = new FakeTime();
        RequestPacer pacer = new RequestPacer(3, time.clock(), time.sleeper());
        pacer.acquire();
        pacer.acquire();

        pacer.setPerMinute(2); // discovery found the real budget is smaller
        pacer.acquire();

        assertEquals(Duration.ofSeconds(60), time.sleeps.get(0),
                "the window keeps its grants: the third call waits for the oldest to expire");
    }

    @Test
    void raisingTheBudgetFreesTheNextAcquire() throws InterruptedException {
        FakeTime time = new FakeTime();
        RequestPacer pacer = new RequestPacer(1, time.clock(), time.sleeper());
        pacer.acquire();

        pacer.setPerMinute(2);
        pacer.acquire();

        assertTrue(time.sleeps.isEmpty());
    }

    @Test
    void aBudgetBelowOneIsRefused() {
        FakeTime time = new FakeTime();
        RequestPacer pacer = new RequestPacer(1, time.clock(), time.sleeper());
        assertThrows(IllegalArgumentException.class, () -> pacer.setPerMinute(0));
    }

    @Test
    void theConservativeStartIsTheFieldEvidenceNumber() {
        assertEquals(10, RequestPacer.CONSERVATIVE_START, "demo keys enforce 10/min — never assume 30");
    }

    @Test
    void aBudgetOfOneIsTheFloorNotARefusal() throws InterruptedException {
        FakeTime time = new FakeTime();
        RequestPacer pacer = new RequestPacer(2, time.clock(), time.sleeper());

        pacer.setPerMinute(1); // discovery can legitimately land here (allowance <= headroom + 1)
        pacer.acquire();
        pacer.acquire();

        assertEquals(1, time.sleeps.size(), "one request per window at the floor");
        assertEquals(Duration.ofSeconds(60), time.sleeps.get(0));
    }
}
