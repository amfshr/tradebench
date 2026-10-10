package dev.amfshr.tradebench.marketdata.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.ig.stream.Ohlc;
import dev.amfshr.tradebench.ig.stream.SealedBarUpdate;
import dev.amfshr.tradebench.ig.stream.TickUpdate;
import dev.amfshr.tradebench.marketdata.supervise.MarketTelemetry;

class BuffersTest {

    private static final String DAX = "IX.D.DAX.DAILY.IP";
    private static final String FTSE = "IX.D.FTSE.DAILY.IP";

    private long now; // what the injected monotonic clock reads

    private Buffers buffers(int tickCapacity) {
        return new Buffers(tickCapacity, () -> now);
    }

    private static TickUpdate tick(long second, String flag) {
        return tick(DAX, second, flag);
    }

    private static TickUpdate tick(String epic, long second, String flag) {
        return new TickUpdate(epic, Instant.ofEpochSecond(second),
                new BigDecimal("1.0"), new BigDecimal("2.0"), flag);
    }

    private static SealedBarUpdate bar(long second) {
        return new SealedBarUpdate(DAX, Instant.ofEpochSecond(second),
                new Ohlc(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE),
                new Ohlc(BigDecimal.TWO, BigDecimal.TWO, BigDecimal.TWO, BigDecimal.TWO),
                null);
    }

    @Test
    void ticksShedOldestAtCapacityAndCountTheLoss() {
        Buffers queues = buffers(2);

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
        Buffers queues = buffers(10);

        queues.onTick(tick(1, "DEAL"));
        queues.onTick(tick(2, "DEAL"));
        queues.onTick(tick(3, "CLOSED"));

        assertEquals(new Buffers.StateChange(DAX, Instant.ofEpochSecond(1), "DEAL"),
                queues.peekStateChangeNow());
        queues.removeStateChangeNow();
        assertEquals(new Buffers.StateChange(DAX, Instant.ofEpochSecond(3), "CLOSED"),
                queues.peekStateChangeNow());
        queues.removeStateChangeNow();
        assertNull(queues.peekStateChangeNow());
    }

    @Test
    void malformedUpdatesAreCounted() {
        Buffers queues = buffers(10);
        queues.onMalformed(DAX, "PRICE:X:" + DAX);
        queues.onMalformed(DAX, "PRICE:X:" + DAX);
        assertEquals(2, queues.malformedUpdates());
    }

    @Test
    void freshnessIsUnknownUntilAMarketsFirstTickAndBar() {
        Buffers queues = buffers(10);

        assertEquals(Long.MIN_VALUE, queues.lastTickMono(DAX));
        assertEquals(Long.MIN_VALUE, queues.lastBarMono(DAX));
        assertNull(queues.dealFlag(DAX));
    }

    @Test
    void freshnessStampsArrivalOnTheMonotonicClockPerMarket() {
        Buffers queues = buffers(10);
        now = 1_000L;
        queues.onTick(tick(1, "DEAL"));
        now = 2_000L;
        queues.onSealedBar(bar(60));
        now = 3_000L;
        queues.onTick(tick(FTSE, 2, "CLOSED"));

        assertEquals(1_000L, queues.lastTickMono(DAX),
                "arrival on the monotonic clock — never the tick's own wall timestamp");
        assertEquals(2_000L, queues.lastBarMono(DAX));
        assertEquals(3_000L, queues.lastTickMono(FTSE));
        assertEquals(Long.MIN_VALUE, queues.lastBarMono(FTSE), "per market: FTSE has no bar yet");
        assertEquals("DEAL", queues.dealFlag(DAX));
        assertEquals("CLOSED", queues.dealFlag(FTSE));
    }

    @Test
    void countsAreKeptPerMarketWithDataTimeLastSeen() {
        Buffers queues = buffers(10);
        queues.onTick(tick(1, "DEAL"));
        queues.onTick(tick(2, "DEAL"));
        queues.onTick(tick(FTSE, 3, "DEAL"));
        queues.onSealedBar(bar(60));

        MarketTelemetry.MarketCounts dax = queues.countsFor(DAX);
        assertEquals(2, dax.ticks());
        assertEquals(1, dax.bars());
        assertEquals(Instant.ofEpochSecond(2), dax.lastTickAt(), "the tick's own time (D38), not arrival");
        assertEquals(Instant.ofEpochSecond(60), dax.lastBarAt(), "the bar's label — its start");
        MarketTelemetry.MarketCounts ftse = queues.countsFor(FTSE);
        assertEquals(1, ftse.ticks());
        assertEquals(0, ftse.bars());
        assertNull(ftse.lastBarAt());
        assertEquals(MarketTelemetry.MarketCounts.NONE, queues.countsFor("IX.D.NEVER.SEEN.IP"));
    }

    @Test
    void aShedTickIsChargedToItsOwnMarketNotTheArrivingOne() {
        Buffers queues = buffers(2);
        queues.onTick(tick(1, "DEAL"));        // DAX
        queues.onTick(tick(2, "DEAL"));        // DAX — the queue is now full
        queues.onTick(tick(FTSE, 3, "DEAL"));  // sheds the oldest: a DAX tick

        assertEquals(1, queues.droppedTicks());
        assertEquals(1, queues.countsFor(DAX).dropped());
        assertEquals(0, queues.countsFor(FTSE).dropped(), "FTSE arrived; DAX paid");
    }

    @Test
    void malformedUpdatesAreChargedToTheirMarket() {
        Buffers queues = buffers(10);
        queues.onMalformed(DAX, "PRICE:AB12CE:" + DAX);
        queues.onMalformed(FTSE, "CHART:" + FTSE + ":1MINUTE");

        assertEquals(2, queues.malformedUpdates());
        assertEquals(1, queues.countsFor(DAX).malformed());
        assertEquals(1, queues.countsFor(FTSE).malformed());
    }

    @Test
    void pendingWritesIsEverythingQueuedForThePump() {
        Buffers queues = buffers(10);
        queues.onSealedBar(bar(60));
        queues.onTick(tick(1, "DEAL"));
        queues.onTick(tick(2, "DEAL"));

        assertEquals(3, queues.pendingWrites(), "one bar + two ticks");
        queues.removeBarNow();
        assertEquals(2, queues.pendingWrites());
    }
}
