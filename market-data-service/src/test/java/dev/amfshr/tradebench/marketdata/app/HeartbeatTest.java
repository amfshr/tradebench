package dev.amfshr.tradebench.marketdata.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** E1-T7, B15's judgment 1: a heartbeat's steps, in order — and none silently dropped. */
class HeartbeatTest {

    private final List<String> did = new ArrayList<>();

    @Test
    void aBeatChecksTheLockPublishesPingsAndSaysTheSummaryInThatOrder() {
        new Heartbeat(() -> did.add("lock"), () -> did.add("publish"), () -> did.add("ping"),
                () -> "ticks=1", line -> did.add("log " + line)).beat();

        assertEquals(List.of("lock", "publish", "ping", "log ticks=1"), did);
    }

    @Test
    void anInstanceWithNoLockOrNoPingSkipsThoseSteps() {
        new Heartbeat(null, () -> did.add("publish"), null, () -> "ticks=1", line -> did.add("log " + line)).beat();

        assertEquals(List.of("publish", "log ticks=1"), did);
    }
}
