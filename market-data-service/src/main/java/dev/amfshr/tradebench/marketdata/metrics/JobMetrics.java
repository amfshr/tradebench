package dev.amfshr.tradebench.marketdata.metrics;

import static dev.amfshr.tradebench.marketdata.metrics.CollectorMetrics.JOB;
import static dev.amfshr.tradebench.marketdata.metrics.CollectorMetrics.MARKET;
import static dev.amfshr.tradebench.marketdata.metrics.CollectorMetrics.ORIGIN;
import static dev.amfshr.tradebench.marketdata.metrics.CollectorMetrics.OUTCOME;
import static dev.amfshr.tradebench.marketdata.metrics.CollectorMetrics.SOURCE;
import static dev.amfshr.tradebench.marketdata.metrics.CollectorMetrics.USER;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;

import dev.amfshr.tradebench.marketdata.jobs.JobRun;
import dev.amfshr.tradebench.marketdata.jobs.JobRunStore;
import dev.amfshr.tradebench.marketdata.jobs.MarketDay;

/**
 * The daily jobs' metrics (E1-T16), read from each run as it is recorded — the {@code job_runs} row is
 * the truth, so a run is counted once it is stored, and a heal's numbers come from its own detail. The IG
 * allowance is the account's, whoever spends it: the heal's points spent stand beside it to tell them apart.
 */
public final class JobMetrics {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long UNKNOWN = Long.MIN_VALUE;

    private final Attributes dimensions;
    private final LongCounter runs;
    private final DoubleHistogram duration;
    private final LongCounter healMinutes;
    private final LongCounter pointsSpent;
    private final LongCounter gapMinutes;
    private final AtomicLong allowance = new AtomicLong(UNKNOWN);

    public JobMetrics(Meter meter, String user, String source) {
        this.dimensions = Attributes.of(USER, user, SOURCE, source);
        this.runs = meter.counterBuilder(CollectorMetrics.JOB_RUNS).setUnit("{run}")
                .setDescription("Daily job runs recorded, by job and outcome").build();
        this.duration = meter.histogramBuilder(CollectorMetrics.JOB_DURATION).setUnit("s")
                .setDescription("Seconds a daily job run took").setExplicitBucketBoundariesAdvice(
                        List.of(0.1, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0, 120.0, 300.0, 600.0)).build();
        this.healMinutes = meter.counterBuilder(CollectorMetrics.HEAL_MINUTES).setUnit("min")
                .setDescription("Minutes the heal looked for: healed from IG's history, or absent at IG too").build();
        this.pointsSpent = meter.counterBuilder(CollectorMetrics.HEAL_POINTS_SPENT).setUnit("{point}")
                .setDescription("IG historical-data points the heal spent").build();
        this.gapMinutes = CollectorMetrics.gapMinutes(meter);
        meter.gaugeBuilder(CollectorMetrics.IG_ALLOWANCE_REMAINING).ofLongs().setUnit("{point}")
                .setDescription("The IG account's remaining weekly historical allowance, as the heal last read it")
                .buildWithCallback(m -> {
                    long known = allowance.get();
                    if (known != UNKNOWN) {
                        m.record(known, dimensions);
                    }
                });
    }

    /** Counts each run once {@code delegate} has stored it. */
    public JobRunStore counting(JobRunStore delegate) {
        return new JobRunStore() {
            @Override
            public List<JobRun> runs(String job, MarketDay day) {
                return delegate.runs(job, day);
            }

            @Override
            public void record(JobRun run) {
                delegate.record(run);
                count(run);
            }
        };
    }

    private void count(JobRun run) {
        Attributes market = dimensions.toBuilder().put(MARKET, run.day().epic()).build();
        Attributes outcome = market.toBuilder().put(JOB, run.job()).put(OUTCOME, run.succeeded() ? "succeeded" : "failed").build();
        runs.add(1, outcome);
        duration.record(Duration.between(run.startedUtc(), run.finishedUtc()).toNanos() / 1e9, outcome);
        if (!"heal".equals(run.job())) {
            return;
        }
        JsonNode detail;
        try {
            detail = MAPPER.readTree(run.detailJson());
        } catch (Exception e) {
            return; // the row is the truth; a detail this cannot read costs only the heal's numbers
        }
        long healed = detail.path("healedMinutes").asLong(0);
        healMinutes.add(healed, market.toBuilder().put(OUTCOME, "healed").build());
        pointsSpent.add(detail.path("pointsSpent").asLong(0), market);
        if (run.succeeded()) { // a heal that failed part-way knows its spend, not what IG lacked or what it recorded
            long missing = detail.path("missing").asLong(0);
            healMinutes.add(Math.max(0, missing - healed), market.toBuilder().put(OUTCOME, "absent_at_source").build());
            gapMinutes.add(detail.path("gapMinutesAdded").asLong(0), market.toBuilder().put(ORIGIN, "heal").build());
        }
        if (detail.hasNonNull("allowanceRemaining")) {
            allowance.set(detail.get("allowanceRemaining").asLong());
        }
    }
}
