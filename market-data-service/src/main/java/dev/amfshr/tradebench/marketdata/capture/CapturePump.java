package dev.amfshr.tradebench.marketdata.capture;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.Nullable;

import dev.amfshr.tradebench.core.domain.Bar1m;
import dev.amfshr.tradebench.core.domain.Tick;
import dev.amfshr.tradebench.ig.time.Sleeper;

/**
 * Single consumer: bars first (the backbone), then state changes, then ticks. Bars and
 * state changes are removed only after a successful write (ack-after-apply); ticks are
 * best-effort by design (shed-oldest queue). A sink failure stops the pump and is kept in
 * {@link #failure()} — never re-drained into a broken sink, never silent (P9).
 */
public final class CapturePump implements Runnable {

    private static final Duration IDLE_WAIT = Duration.ofMillis(250);
    private static final int TICK_BATCH = 5_000;

    private final CaptureQueues queues;
    private final CaptureSink sink;
    private final Sleeper sleeper;
    private final AtomicLong written = new AtomicLong();
    private volatile boolean running = true;
    private volatile @Nullable RuntimeException failure;

    public CapturePump(CaptureQueues queues, CaptureSink sink, Sleeper sleeper) {
        this.queues = queues;
        this.sink = sink;
        this.sleeper = sleeper;
    }

    int drainOnce() {
        int count = 0;
        Bar1m bar;
        while ((bar = queues.peekBarNow()) != null) {
            sink.write(bar);
            queues.removeBarNow();
            written.incrementAndGet();
            count++;
        }
        CaptureQueues.StateChange state;
        while ((state = queues.peekStateChangeNow()) != null) {
            sink.write(state);
            queues.removeStateChangeNow();
            written.incrementAndGet();
            count++;
        }
        Tick tick;
        for (int i = 0; i < TICK_BATCH && (tick = queues.pollTickNow()) != null; i++) {
            sink.write(tick);
            written.incrementAndGet();
            count++;
        }
        return count;
    }

    void cycle() throws InterruptedException {
        if (drainOnce() == 0) {
            sink.flush();
            sleeper.sleep(IDLE_WAIT);
        }
    }

    @Override
    public void run() {
        try {
            while (running) {
                cycle();
            }
            drainOnce();
            sink.flush();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    public void stop() {
        running = false;
    }

    /** Events actually written to the sink — the heartbeat's honest number. */
    public long writtenCount() {
        return written.get();
    }

    public @Nullable RuntimeException failure() {
        return failure;
    }
}
