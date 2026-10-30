package dev.amfshr.tradebench.marketdata.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;

import dev.amfshr.tradebench.marketdata.jobs.JobRun;
import dev.amfshr.tradebench.marketdata.jobs.JobRunStore;
import dev.amfshr.tradebench.marketdata.jobs.MarketDay;

/** The daily jobs' metrics, read from each run as it is stored (E1-T16). */
class JobMetricsTest {

    private static final String DAX = "IX.D.DAX.DAILY.IP";
    private static final MarketDay DAY = new MarketDay(DAX, LocalDate.of(2026, 10, 29));
    private static final Instant T0 = Instant.parse("2026-10-30T00:14:00Z");
    private static final Attributes DAX_DIMS = Attributes.builder().put("user", "platform").put("source", "ig-stream-demo")
            .put("market", DAX).build();

    private InMemoryMetricReader reader;
    private JobRunStore store;
    private final List<JobRun> stored = new ArrayList<>();
    private boolean refuse;

    @BeforeEach
    void setUp() {
        reader = InMemoryMetricReader.create();
        JobMetrics metrics = new JobMetrics(SdkMeterProvider.builder().registerMetricReader(reader).build().get("test"),
                "platform", "ig-stream-demo");
        store = metrics.counting(new JobRunStore() {
            @Override
            public List<JobRun> runs(String job, MarketDay day) {
                return stored;
            }

            @Override
            public void record(JobRun run) {
                if (refuse) {
                    throw new IllegalStateException("database down");
                }
                stored.add(run);
            }
        });
    }

    private static JobRun heal(String detail) {
        return new JobRun("heal", DAY, T0, T0.plusMillis(2_500), true, detail);
    }

    private MetricData metric(Collection<MetricData> all, String name) {
        return all.stream().filter(m -> m.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no metric " + name));
    }

    private static long value(Collection<LongPointData> points, Attributes attributes) {
        return points.stream().filter(p -> p.getAttributes().equals(attributes)).findFirst()
                .orElseThrow(() -> new AssertionError("no series " + attributes + " in " + points)).getValue();
    }

    private static Attributes with(String key, String value, String key2, String value2) {
        return DAX_DIMS.toBuilder().put(key, value).put(key2, value2).build();
    }

    @Test
    void aRunIsCountedByJobAndOutcomeWithItsDuration() {
        store.record(heal("{}"));
        store.record(new JobRun("archive", DAY, T0, T0.plusSeconds(4), false, "{\"error\":\"boom\"}"));

        Collection<MetricData> all = reader.collectAllMetrics();
        var runs = metric(all, CollectorMetrics.JOB_RUNS).getLongSumData().getPoints();

        assertEquals(1, value(runs, with("job", "heal", "outcome", "succeeded")));
        assertEquals(1, value(runs, with("job", "archive", "outcome", "failed")));
        var durations = metric(all, CollectorMetrics.JOB_DURATION).getHistogramData().getPoints();
        assertEquals(6.5, durations.stream().mapToDouble(p -> p.getSum()).sum(), 1e-9, "2.5 s + 4 s");
    }

    @Test
    void aHealsNumbersComeFromItsOwnDetail() {
        store.record(heal("{\"missing\":9,\"healedMinutes\":2,\"pointsSpent\":2,\"gapMinutesAdded\":5,\"allowanceRemaining\":8484}"));

        Collection<MetricData> all = reader.collectAllMetrics();
        var minutes = metric(all, CollectorMetrics.HEAL_MINUTES).getLongSumData().getPoints();

        assertEquals(2, value(minutes, DAX_DIMS.toBuilder().put("outcome", "healed").build()));
        assertEquals(7, value(minutes, DAX_DIMS.toBuilder().put("outcome", "absent_at_source").build()), "9 looked for, 2 healed");
        assertEquals(2, value(metric(all, CollectorMetrics.HEAL_POINTS_SPENT).getLongSumData().getPoints(), DAX_DIMS));
        assertEquals(5, value(metric(all, CollectorMetrics.GAP_MINUTES).getLongSumData().getPoints(),
                DAX_DIMS.toBuilder().put("origin", "heal").build()));
        assertEquals(8484, metric(all, CollectorMetrics.IG_ALLOWANCE_REMAINING).getLongGaugeData().getPoints().iterator().next()
                .getValue());
    }

    @Test
    void theAllowanceIsAbsentUntilAHealHasReadIt() {
        store.record(heal("{\"missing\":0,\"healedMinutes\":0,\"pointsSpent\":0}"));

        assertTrue(reader.collectAllMetrics().stream().noneMatch(m -> m.getName().equals(CollectorMetrics.IG_ALLOWANCE_REMAINING)
                && !m.getLongGaugeData().getPoints().isEmpty()), "no reading, no series — never a made-up zero");
    }

    @Test
    void anArchiveOrAFailedHealAddsNoHealNumbers() {
        store.record(new JobRun("archive", DAY, T0, T0, true, "{\"missing\":9,\"healedMinutes\":2}"));
        store.record(new JobRun("heal", DAY, T0, T0, false, "{\"missing\":9,\"healedMinutes\":2}"));

        assertTrue(reader.collectAllMetrics().stream().noneMatch(m -> m.getName().equals(CollectorMetrics.HEAL_MINUTES)
                && !m.getLongSumData().getPoints().isEmpty()));
    }

    @Test
    void aRunTheStoreRefusedIsNotCountedForTheRowIsTheTruth() {
        refuse = true;
        assertThrows(IllegalStateException.class, () -> store.record(heal("{\"missing\":1}")));

        assertTrue(reader.collectAllMetrics().stream().noneMatch(m -> m.getName().equals(CollectorMetrics.JOB_RUNS)
                && !m.getLongSumData().getPoints().isEmpty()), "the runner retries it; counted once it lands");
    }
}
