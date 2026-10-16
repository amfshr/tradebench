package dev.amfshr.tradebench.marketdata.heal;

import java.time.Instant;

/** A recorded deal-flag transition ({@code market_state_change}). */
public record FlagChange(Instant at, String flag) {
}
