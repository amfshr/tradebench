package dev.amfshr.tradebench.marketdata.scenario;

import java.time.Duration;
import java.time.Instant;

import dev.amfshr.tradebench.marketdata.events.ServiceEvent;
import dev.amfshr.tradebench.marketdata.store.CaptureStore;
import dev.amfshr.tradebench.marketdata.store.EventLog;
import dev.amfshr.tradebench.marketdata.store.GapStore;
import dev.amfshr.tradebench.marketdata.store.StatusStore;
import dev.amfshr.tradebench.marketdata.testutil.RecordingEventLog;
import dev.amfshr.tradebench.marketdata.testutil.RecordingGapStore;
import dev.amfshr.tradebench.marketdata.testutil.RecordingStatusStore;
import dev.amfshr.tradebench.marketdata.testutil.ScriptedCaptureStore;

/** The policy scenarios' stores: Postgres's failure semantics without its SQL, weather on cue. */
final class ScriptedStores implements Stores {

    private final ScriptedCaptureStore sink = new ScriptedCaptureStore();
    private final RecordingEventLog events = new RecordingEventLog();
    private final RecordingGapStore gaps = new RecordingGapStore();
    private final RecordingStatusStore status = new RecordingStatusStore();

    @Override
    public CaptureStore sink() {
        return sink;
    }

    @Override
    public EventLog events() {
        return events;
    }

    @Override
    public GapStore gaps() {
        return gaps;
    }

    @Override
    public StatusStore status() {
        return status;
    }

    @Override
    public void apply(Event event) {
        switch (event) {
            case Event.DbDown() -> {
                sink.down = true;
                events.failWrites = true;
                gaps.down = true;
                status.down = true;
            }
            case Event.DbUp() -> {
                sink.down = false;
                sink.rejectWrites = false;
                events.failWrites = false;
                gaps.down = false;
                status.down = false;
            }
            case Event.DbBroken() -> sink.terminal = true;
            case Event.DbWritesRefused() -> sink.rejectWrites = true;
            default -> throw new IllegalArgumentException("not a database event: " + event);
        }
    }

    @Override
    public void collect(Observed observed, Instant start) {
        for (ServiceEvent event : events.written) {
            observed.events.add(new Observed.Seen(Duration.between(start, event.eventTimeUtc()),
                    event.type(), event.epic(), event.detail()));
        }
        observed.landed.addAll(sink.landed);
        observed.sinkRecoveries = sink.recoveries;
        observed.ticksHeldAtEnd = sink.held();
    }

    @Override
    public void close() {
    }
}
