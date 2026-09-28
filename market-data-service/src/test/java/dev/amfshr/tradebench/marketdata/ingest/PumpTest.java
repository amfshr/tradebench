package dev.amfshr.tradebench.marketdata.ingest;

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
import dev.amfshr.tradebench.marketdata.store.CaptureStore;

class PumpTest {

    private static final String DAX = "IX.D.DAX.DAILY.IP";
    private static final Sleeper NO_SLEEP = duration -> {
    };

    private static class RecordingSink implements CaptureStore {
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
        public void write(Buffers.StateChange stateChange) {
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
        Buffers queues = new Buffers(10);
        RecordingSink sink = new RecordingSink();
        queues.onTick(tick(10));
        queues.onTick(tick(11));
        queues.onSealedBar(bar(60));
        Pump pump = new Pump(queues, sink, NO_SLEEP);

        int written = pump.drainOnce();

        assertEquals(List.of("bar@60", "state:DEAL", "tick@10", "tick@11"), sink.order,
                "bars are the must-persist backbone — they drain first");
        assertEquals(4, written);
        assertEquals(4, pump.writtenCount(), "the heartbeat's honest number");
    }

    @Test
    void idleCycleFlushesTheSink() throws InterruptedException {
        RecordingSink sink = new RecordingSink();
        Pump pump = new Pump(new Buffers(10), sink, NO_SLEEP);

        pump.cycle();

        assertEquals(1, sink.flushes, "idle moments flush buffered writes to disk");
    }

    @Test
    void stoppedRunStillDrainsTheTail() {
        Buffers queues = new Buffers(10);
        RecordingSink sink = new RecordingSink();
        queues.onSealedBar(bar(60));
        Pump pump = new Pump(queues, sink, NO_SLEEP);
        pump.stop();

        pump.run();

        assertEquals(List.of("bar@60"), sink.order,
                "the day's last minute must survive shutdown");
        assertEquals(1, sink.flushes);
    }

    @Test
    void sinkFailureStopsThePumpKeepsTheBarAndKeepsTheCause() {
        Buffers queues = new Buffers(10);
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
        Pump pump = new Pump(queues, failingSink, NO_SLEEP);

        pump.run();

        assertSame(boom, pump.failure(), "the cause survives for the runner to report");
        assertEquals(1, failingSink.barAttempts,
                "no re-drain into a broken sink — it would mask the original failure");
        assertNotNull(queues.peekBarNow(),
                "ack-after-apply: the unwritten bar stays queued, not silently lost");
    }
}
