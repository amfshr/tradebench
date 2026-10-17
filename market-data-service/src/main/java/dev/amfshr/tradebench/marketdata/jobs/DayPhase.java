package dev.amfshr.tradebench.marketdata.jobs;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** One phase of the daily job (the heal, the archive). Runs on the jobs thread only. */
public interface DayPhase {

    /** The {@code job_runs.job_name} this phase's rows carry. */
    String name();

    /** Do the phase's work for one market-day and say what was done; any exception is the phase
     * failing, recorded as a failed row — never thrown past the runner. */
    ObjectNode run(MarketDay day) throws Exception;
}
