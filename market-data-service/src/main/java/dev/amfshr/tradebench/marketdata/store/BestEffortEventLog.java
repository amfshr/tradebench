package dev.amfshr.tradebench.marketdata.store;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import dev.amfshr.tradebench.marketdata.events.ServiceEvent;

/**
 * Tier 2 by ruling (D27): a write the log refuses is counted and logged, never thrown — the
 * product plane never halts, and no sweep dies, to protect a breadcrumb. The one place that
 * policy lives (E1-T10 #26); a producer that takes this type cannot be handed a throwing log.
 * One per producer, so the heartbeat can say whose writes failed.
 */
public final class BestEffortEventLog implements EventLog {
    private final EventLog delegate;
    private final Consumer<String> log;
    private final AtomicLong failures = new AtomicLong();

    public BestEffortEventLog(EventLog delegate, Consumer<String> log) {
        this.delegate = delegate;
        this.log = log;
    }

    @Override
    public void write(ServiceEvent event) {
        try {
            delegate.write(event);
        } catch (RuntimeException e) {
            failures.incrementAndGet();
            log.accept("event not written (" + event.type() + "): " + e);
        }
    }

    /** Writes refused and swallowed — nonzero is the alarm. */
    public long failures() {
        return failures.get();
    }
}
