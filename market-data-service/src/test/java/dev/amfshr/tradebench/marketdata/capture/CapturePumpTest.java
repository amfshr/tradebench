package dev.amfshr.tradebench.marketdata.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

class CapturePumpTest {

    private static final String DAX = "IX.D.DAX.DAILY.IP";

    private static final class RecordingSink implements CaptureSink {
        final List<String> order = new ArrayList<>();

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
        }

        @Override
        public void close() {
        }
    }

    @Test
    void drainsBarsFirstThenStateThenTicks() {
        CaptureQueues queues = new CaptureQueues(10);
        RecordingSink sink = new RecordingSink();
        queues.onTick(new TickUpdate(DAX, Instant.ofEpochSecond(10),
                BigDecimal.ONE, BigDecimal.TWO, "DEAL"));
        queues.onTick(new TickUpdate(DAX, Instant.ofEpochSecond(11),
                BigDecimal.ONE, BigDecimal.TWO, "DEAL"));
        queues.onSealedBar(new SealedBarUpdate(DAX, Instant.ofEpochSecond(60),
                new Ohlc(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE),
                new Ohlc(BigDecimal.TWO, BigDecimal.TWO, BigDecimal.TWO, BigDecimal.TWO),
                null));

        int written = new CapturePump(queues, sink).drainOnce();

        assertEquals(List.of("bar@60", "state:DEAL", "tick@10", "tick@11"), sink.order,
                "bars are the must-persist backbone — they drain first");
        assertEquals(4, written);
    }

    @Test
    void idleDrainWritesNothing() {
        RecordingSink sink = new RecordingSink();
        assertEquals(0, new CapturePump(new CaptureQueues(10), sink).drainOnce());
        assertEquals(List.of(), sink.order);
    }
}
