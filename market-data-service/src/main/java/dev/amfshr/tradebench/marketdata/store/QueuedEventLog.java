package dev.amfshr.tradebench.marketdata.store;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import dev.amfshr.tradebench.marketdata.events.ServiceEvent;

/**
 * Tier-2 writes off the sweep thread (D29 (3)): events go onto a bounded queue and one writer
 * hands them to the real log, so a sweep never waits on Postgres. A full queue refuses the
 * event — the caller counts the drop as it counts any failed write — and a write the real log
 * refuses is counted here and dropped, never retried: a breadcrumb, and coverage truth is
 * recomputed from {@code bars_1m}. The writer thread runs {@link #run()}; the scenario harness
 * calls {@link #drainOnce()} in its place.
 */
public final class QueuedEventLog implements EventLog, Runnable {

    private static final Duration POLL = Duration.ofMillis(250);

    private final EventLog delegate;
    private final int capacity;
    private final BlockingQueue<ServiceEvent> queue;
    private final AtomicLong writeFailures = new AtomicLong();
    private volatile boolean running = true;

    public QueuedEventLog(EventLog delegate, int capacity) {
        this.delegate = delegate;
        this.capacity = capacity;
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    @Override
    public void write(ServiceEvent event) {
        if (!queue.offer(event)) {
            throw new IllegalStateException("event queue full at " + capacity + " — dropping " + event.type());
        }
    }

    /** The writer's turn: every queued event handed on, in order; one the log refuses is counted
     * and dropped. Returns how many were handed on. */
    public int drainOnce() {
        int handedOn = 0;
        ServiceEvent event;
        while ((event = queue.poll()) != null) {
            deliver(event);
            handedOn++;
        }
        return handedOn;
    }

    @Override
    public void run() {
        try {
            while (running) {
                ServiceEvent event = queue.poll(POLL.toMillis(), TimeUnit.MILLISECONDS);
                if (event != null) {
                    deliver(event);
                    drainOnce();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Stop the writer after its current poll; whatever is still queued is for {@link #drainOnce()}. */
    public void stop() {
        running = false;
    }

    public int queued() {
        return queue.size();
    }

    /** Events the real log refused — dropped, loud in the heartbeat. */
    public long writeFailures() {
        return writeFailures.get();
    }

    private void deliver(ServiceEvent event) {
        try {
            delegate.write(event);
        } catch (RuntimeException e) {
            writeFailures.incrementAndGet();
        }
    }
}
