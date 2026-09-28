package dev.amfshr.tradebench.core.events;

import dev.amfshr.tradebench.core.domain.MarketEvent;

/** Consumes the ordered event stream. Implementations decide their own threading. */
public interface EventListener {

    void onEvent(MarketEvent event);
}
