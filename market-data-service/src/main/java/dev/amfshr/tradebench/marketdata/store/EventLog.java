package dev.amfshr.tradebench.marketdata.store;

import dev.amfshr.tradebench.marketdata.events.ServiceEvent;

/** Appends to the audit log (service_events v2). Segregated so a producer injects only this. */
public interface EventLog {

    void write(ServiceEvent event);
}
