package dev.amfshr.tradebench.marketdata.jobs;

import java.time.LocalDate;

/** One market's UTC calendar day — the unit a phase works on and a {@code job_runs} row records. */
public record MarketDay(String epic, LocalDate day) {
}
