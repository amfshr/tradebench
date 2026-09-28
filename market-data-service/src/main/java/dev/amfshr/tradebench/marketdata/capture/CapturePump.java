package dev.amfshr.tradebench.marketdata.capture;

import java.time.Duration;

import dev.amfshr.tradebench.core.domain.Bar1m;
import dev.amfshr.tradebench.core.domain.Tick;

/** Single consumer thread: drains bars first (the backbone), then state changes, then ticks. */
public final class CapturePump implements Runnable {

    private static final Duration IDLE_WAIT = Duration.ofMillis(250);
    private static final int TICK_BATCH = 5_000;

    private final CaptureQueues queues;
    private final CaptureSink sink;
    private volatile boolean running = true;

    public CapturePump(CaptureQueues queues, CaptureSink sink) {
        this.queues = queues;
        this.sink = sink;
    }

    int drainOnce() {
        int written = 0;
        Bar1m bar;
        while ((bar = queues.pollBarNow()) != null) {
            sink.write(bar);
            written++;
        }
        CaptureQueues.StateChange state;
        while ((state = queues.pollStateChangeNow()) != null) {
            sink.write(state);
            written++;
        }
        Tick tick;
        for (int i = 0; i < TICK_BATCH && (tick = queues.pollTickNow()) != null; i++) {
            sink.write(tick);
            written++;
        }
        return written;
    }

    @Override
    public void run() {
        try {
            while (running) {
                if (drainOnce() == 0) {
                    Bar1m waited = queues.awaitBar(IDLE_WAIT);
                    if (waited != null) {
                        sink.write(waited);
                    }
                    sink.flush();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            drainOnce();
            sink.flush();
        }
    }

    public void stop() {
        running = false;
    }
}
