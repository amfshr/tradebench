package dev.amfshr.tradebench.marketdata.testutil;

import java.util.ArrayList;
import java.util.List;

import dev.amfshr.tradebench.marketdata.events.EventType;
import dev.amfshr.tradebench.marketdata.events.ServiceEvent;
import dev.amfshr.tradebench.marketdata.store.EventLog;

/** An {@link EventLog} that keeps every event in order, and can be told Postgres is down. */
public final class RecordingEventLog implements EventLog {

    public final List<ServiceEvent> written = new ArrayList<>();
    /** When true, every write throws — the Tier-2 failure the belt and the pump must survive. */
    public boolean failWrites;

    @Override
    public void write(ServiceEvent event) {
        if (failWrites) {
            throw new IllegalStateException("service_events unavailable");
        }
        written.add(event);
    }

    public List<ServiceEvent> ofType(EventType type) {
        return written.stream().filter(e -> e.type() == type).toList();
    }

    public long count(EventType type) {
        return written.stream().filter(e -> e.type() == type).count();
    }
}
