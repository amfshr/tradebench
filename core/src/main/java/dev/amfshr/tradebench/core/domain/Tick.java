package dev.amfshr.tradebench.core.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One bid/ask tick — the platform's finest stored truth (PRD §7). The precision path,
 * never the completeness path (D15): tick-exact triggers and the forming bar's final
 * partial minute read these; derived timeframes never do. Market state (DLG_FLAG) is
 * deliberately absent — it routes to the watchdog/state machinery, not per-tick storage
 * (T3 design ruling).
 */
public record Tick(String epic, Instant timestamp, BigDecimal bid, BigDecimal ask)
        implements MarketEvent {

    @Override
    public Instant dataTime() {
        return timestamp;
    }
}
