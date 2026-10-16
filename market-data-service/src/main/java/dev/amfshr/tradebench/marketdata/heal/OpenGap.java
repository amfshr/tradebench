package dev.amfshr.tradebench.marketdata.heal;

import java.time.Instant;

/** A {@code bar_gaps} row not yet healed: minute starts {@code from}..{@code to}, inclusive. */
public record OpenGap(long id, Instant from, Instant to) {
}
