package dev.amfshr.tradebench.marketdata.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.core.domain.Bar1m;
import dev.amfshr.tradebench.core.domain.Tick;
import dev.amfshr.tradebench.ig.stream.Ohlc;
import dev.amfshr.tradebench.ig.stream.SealedBarUpdate;
import dev.amfshr.tradebench.ig.stream.TickUpdate;
import dev.amfshr.tradebench.ig.time.Sleeper;

class CapturePumpTest {

    private static final String DAX = "IX.D.DAX.DAILY.IP";
    private static final Sleeper NO_SLEEP = duration -> {
    };

    private static class RecordingSink implements CaptureSink {
        final List<String> order = new ArrayList<>();
        int flushes;

        @Override
        public void write(Bar1m bar) {
            order.add("bar@" + bar.startUtc().getEpochSecond());
        }

        @Override
        public void write(Tick tick) {
            order.add("tick@" + tick.timestamp().getEpochSecond());
        }

        @Override
        public void write(CaptureQueues.StateChange stateChange) {
            order.add("state:" + stateChange.dealFlag());
        }

        @Override
        public void flush() {
            flushes++;
        }

        @Override
        public void close() {
        }
    }

    private static TickUpdate tick(long second) {
        return new TickUpdate(DAX, Instant.ofEpochSecond(second), BigDecimal.ONE,
                BigDecimal.TWO, "DEAL");
    }

    private static SealedBarUpdate bar(long second) {
        return new SealedBarUpdate(DAX, Instant.ofEpochSecond(second),
                new Ohlc(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE),
                new Ohlc(BigDecimal.TWO, BigDecimal.TWO, BigDecimal.TWO, BigDecimal.TWO),
                null);
    }

    @Test
    void drainsBarsFirstThenStateThenTicksAndCountsWrites() {
        CaptureQueues queues = new CaptureQueues(10);
        RecordingSink sink = new RecordingSink();
        queues.onTick(tick(10));
        queues.onTick(tick(11));
        queues.onSealedBar(bar(60));
        CapturePump pump = new CapturePump(queues, sink, NO_SLEEP);

        int written = pump.drainOnce();

        assertEquals(List.of("bar@60", "state:DEAL", "tick@10", "tick@11"), sink.order,
                "bars are the must-persist backbone — they drain first");
        assertEquals(4, written);
        assertEquals(4, pump.writtenCount(), "the heartbeat's honest number");
    }

    @Test
    void idleCycleFlushesTheSink() throws InterruptedException {
        RecordingSink sink = new RecordingSink();
        CapturePump pump = new CapturePump(new CaptureQueues(10), sink, NO_SLEEP);

        pump.cycle();

        assertEquals(1, sink.flushes, "idle moments flush buffered writes to disk");
    }

    @Test
    void stoppedRunStillDrainsTheTail() {
        CaptureQueues queues = new CaptureQueues(10);
        RecordingSink sink = new RecordingSink();
        queues.onSealedBar(bar(60));
        CapturePump pump = new CapturePump(queues, sink, NO_SLEEP);
        pump.stop();

        pump.run();

        assertEquals(List.of("bar@60"), sink.order,
                "the day's last minute must survive shutdown");
        assertEquals(1, sink.flushes);
    }

    @Test
    void sinkFailureStopsThePumpKeepsTheBarAndKeepsTheCause() {
        CaptureQueues queues = new CaptureQueues(10);
        queues.onSealedBar(bar(60));
        UncheckedIOException boom = new UncheckedIOException("disk full",
                new java.io.IOException("disk full"));
        var failingSink = new RecordingSink() {
            int barAttempts;

            @Override
            public void write(Bar1m bar) {
                barAttempts++;
                throw boom;
            }
        };
        CapturePump pump = new CapturePump(queues, failingSink, NO_SLEEP);

        pump.run();

        assertSame(boom, pump.failure(), "the cause survives for the runner to report");
        assertEquals(1, failingSink.barAttempts,
                "no re-drain into a broken sink — it would mask the original failure");
        assertNotNull(queues.peekBarNow(),
                "ack-after-apply: the unwritten bar stays queued, not silently lost");
    }
}
