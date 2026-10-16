package dev.amfshr.tradebench.marketdata.jobs;

import java.util.List;

/** Where runs are recorded — {@code job_runs} in production. Failures surface as unchecked
 * exceptions; the runner abandons its tick on one and tries again next wake. */
public interface JobRunStore {

    /** Every recorded attempt of {@code job} for {@code day}, oldest first. */
    List<JobRun> runs(String job, MarketDay day);

    void record(JobRun run);
}
