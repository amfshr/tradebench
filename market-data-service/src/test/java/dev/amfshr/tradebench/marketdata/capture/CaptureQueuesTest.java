package dev.amfshr.tradebench.marketdata.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.ig.stream.TickUpdate;

class CaptureQueuesTest {

    private static final String DAX = "IX.D.DAX.DAILY.IP";

    private static TickUpdate tick(long second, String flag) {
        return new TickUpdate(DAX, Instant.ofEpochSecond(second),
                new BigDecimal("1.0"), new BigDecimal("2.0"), flag);
    }

    @Test
    void ticksShedOldestAtCapacityAndCountTheLoss() {
        CaptureQueues queues = new CaptureQueues(2);

        queues.onTick(tick(1, "DEAL"));
        queues.onTick(tick(2, "DEAL"));
        queues.onTick(tick(3, "DEAL"));

        assertEquals(Instant.ofEpochSecond(2), queues.pollTickNow().timestamp(),
                "oldest tick shed — freshest data wins, backpressure never reaches the socket");
        assertEquals(Instant.ofEpochSecond(3), queues.pollTickNow().timestamp());
        assertNull(queues.pollTickNow());
        assertEquals(1, queues.droppedTicks());
        assertEquals(3, queues.tickCount());
    }

    @Test
    void dealFlagChangesAreRecordedOnceIncludingTheFirst() {
        CaptureQueues queues = new CaptureQueues(10);

        queues.onTick(tick(1, "DEAL"));
        queues.onTick(tick(2, "DEAL"));
        queues.onTick(tick(3, "CLOSED"));

        assertEquals(new CaptureQueues.StateChange(DAX, Instant.ofEpochSecond(1), "DEAL"),
                queues.pollStateChangeNow());
        assertEquals(new CaptureQueues.StateChange(DAX, Instant.ofEpochSecond(3), "CLOSED"),
                queues.pollStateChangeNow());
        assertNull(queues.pollStateChangeNow());
    }

    @Test
    void malformedUpdatesAreCounted() {
        CaptureQueues queues = new CaptureQueues(10);
        queues.onMalformed("PRICE:X:Y");
        queues.onMalformed("PRICE:X:Y");
        assertEquals(2, queues.malformedUpdates());
    }
}
