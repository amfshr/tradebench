package dev.amfshr.tradebench.marketdata.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;

import dev.amfshr.tradebench.core.domain.Bar1m;
import dev.amfshr.tradebench.core.domain.OhlcPrices;
import dev.amfshr.tradebench.core.domain.Tick;
import dev.amfshr.tradebench.ig.stream.Ohlc;
import dev.amfshr.tradebench.ig.stream.SealedBarUpdate;
import dev.amfshr.tradebench.ig.stream.TickUpdate;
import dev.amfshr.tradebench.marketdata.events.EventType;
import dev.amfshr.tradebench.marketdata.events.ServiceEvent;
import dev.amfshr.tradebench.marketdata.ingest.Buffers;
import dev.amfshr.tradebench.marketdata.jobs.JobRun;
import dev.amfshr.tradebench.marketdata.jobs.JobRunStore;
import dev.amfshr.tradebench.marketdata.jobs.MarketDay;
import dev.amfshr.tradebench.marketdata.store.CaptureStore;
import dev.amfshr.tradebench.marketdata.store.EventLog;

/** What the collector records, read back from an in-memory OpenTelemetry SDK (E1-T16). */
class CollectorMetricsTest {

    private static final String DAX = "IX.D.DAX.DAILY.IP";
    private static final String NDX = "IX.D.NASDAQ.CASH.IP";
    private static final Instant T0 = Instant.parse("2026-10-30T08:00:00Z");
    private static final Attributes DAX_DIMS = Attributes.builder().put("market", DAX).put("user", "platform")
            .put("source", "ig-stream-demo").build();

    private InMemoryMetricReader reader;
    private CollectorMetrics metrics;
    private SdkMeterProvider provider;
    private long now; // the monotonic clock the buffer and the gauges read
    private Buffers buffers;

    @BeforeEach
    void setUp() {
        reader = InMemoryMetricReader.create();
        provider = SdkMeterProvider.builder().registerMetricReader(reader).build();
        metrics = new CollectorMetrics(provider.get("test"), "platform", "ig-stream-demo", List.of(DAX));
        buffers = new Buffers(10, () -> now, metrics.tickGaps());
        metrics.observe(buffers, () -> now);
    }

    private static TickUpdate tick(String epic, double secondsAfterT0, String bid) {
        return new TickUpdate(epic, T0.plusNanos((long) (secondsAfterT0 * 1e9)), new BigDecimal(bid),
                new BigDecimal(bid).add(BigDecimal.TEN), "DEAL");
    }

    private static SealedBarUpdate bar(int minute) {
        Ohlc o = new Ohlc(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
        return new SealedBarUpdate(DAX, T0.plusSeconds(60L * minute), o, o, null);
    }

    private MetricData metric(Collection<MetricData> all, String name) {
        return all.stream().filter(m -> m.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no metric " + name + " in " + all.stream().map(MetricData::getName).toList()));
    }

    @Test
    void theCountsArePerMarketWithItsUserAndSourceAndARepeatIsNotCounted() {
        buffers.onTick(tick(DAX, 0, "25000.0"));
        buffers.onTick(tick(DAX, 1, "25000.5"));
        buffers.onTick(tick(DAX, 1, "25000.5")); // a snapshot's repeat: not news
        buffers.onTick(tick(DAX, 2, "25001.0"));
        buffers.onSealedBar(bar(1));
        buffers.onSealedBar(bar(2));
        buffers.onMalformed(DAX, "PRICE:AB12CE:" + DAX);

        Collection<MetricData> all = reader.collectAllMetrics();

        var ticks = metric(all, CollectorMetrics.TICKS).getLongSumData();
        assertTrue(ticks.isMonotonic(), "a counter, so Grafana sees _total");
        assertEquals(1, ticks.getPoints().size(), "one series: the watched market");
        assertEquals(3, ticks.getPoints().iterator().next().getValue());
        assertEquals(DAX_DIMS, ticks.getPoints().iterator().next().getAttributes());
        assertEquals(2, metric(all, CollectorMetrics.BARS).getLongSumData().getPoints().iterator().next().getValue());
        assertEquals(1, metric(all, CollectorMetrics.UPDATES_MALFORMED).getLongSumData().getPoints().iterator().next().getValue());
        assertEquals(0, metric(all, CollectorMetrics.TICKS_DROPPED).getLongSumData().getPoints().iterator().next().getValue());
    }

    @Test
    void aShedTickIsCountedAsDropped() {
        Buffers small = new Buffers(1, () -> now);
        SdkMeterProvider provider = SdkMeterProvider.builder().registerMetricReader(reader = InMemoryMetricReader.create()).build();
        CollectorMetrics metrics = new CollectorMetrics(provider.get("test"), "platform", "ig-stream-demo", List.of(DAX));
        metrics.observe(small, () -> now);
        small.onTick(tick(DAX, 0, "1.0"));
        small.onTick(tick(DAX, 1, "2.0")); // the queue holds one: the first is shed

        assertEquals(1, metric(reader.collectAllMetrics(), CollectorMetrics.TICKS_DROPPED).getLongSumData()
                .getPoints().iterator().next().getValue());
    }

    @Test
    void theAgesAreSecondsSinceTheLastArrivalAndAbsentBeforeTheFirst() {
        assertTrue(reader.collectAllMetrics().stream().noneMatch(m -> m.getName().equals(CollectorMetrics.TICK_AGE)
                && !m.getDoubleGaugeData().getPoints().isEmpty()), "no tick yet, no age");
        now = 10_000_000_000L;
        buffers.onTick(tick(DAX, 0, "1.0"));
        now = 15_000_000_000L;
        buffers.onSealedBar(bar(1));
        now = 25_500_000_000L;

        Collection<MetricData> all = reader.collectAllMetrics();

        assertEquals(15.5, metric(all, CollectorMetrics.TICK_AGE).getDoubleGaugeData().getPoints().iterator().next().getValue());
        assertEquals(10.5, metric(all, CollectorMetrics.BAR_AGE).getDoubleGaugeData().getPoints().iterator().next().getValue());
        assertEquals("s", metric(all, CollectorMetrics.TICK_AGE).getUnit());
    }

    @Test
    void theQueueGaugeIsWhatThePumpHasNotWritten() {
        buffers.onTick(tick(DAX, 0, "1.0"));
        buffers.onTick(tick(DAX, 1, "2.0"));
        buffers.onSealedBar(bar(1));

        assertEquals(3, metric(reader.collectAllMetrics(), CollectorMetrics.QUEUE_PENDING).getLongGaugeData()
                .getPoints().iterator().next().getValue());
    }

    @Test
    void theTickGapIsIgsTimeBetweenTicksThatAreNewsInTheRuledBuckets() {
        buffers.onTick(tick(DAX, 0, "1.0"));     // the first: no gap yet
        buffers.onTick(tick(DAX, 2.5, "2.0"));   // 2.5 s
        buffers.onTick(tick(DAX, 2.5, "2.0"));   // a repeat: not news, no gap
        buffers.onTick(tick(DAX, 62.5, "3.0"));  // 60 s — exactly a boundary
        buffers.onTick(tick(NDX, 0, "4.0"));     // an unwatched market: not recorded
        buffers.onTick(tick(NDX, 9, "5.0"));

        Collection<HistogramPointData> points = metric(reader.collectAllMetrics(), CollectorMetrics.TICK_GAP)
                .getHistogramData().getPoints();
        assertEquals(1, points.size(), "one series — the watched market; the unwatched one records nothing");
        HistogramPointData p = points.iterator().next();

        assertEquals(List.of(0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0, 90.0, 120.0, 300.0), p.getBoundaries());
        assertEquals(2, p.getCount());
        assertEquals(62.5, p.getSum(), 1e-9);
        // buckets are (lower, upper]: 2.5 lands in (2, 5], 60 in (30, 60]
        assertEquals(List.of(0L, 0L, 0L, 0L, 0L, 1L, 0L, 0L, 1L, 0L, 0L, 0L, 0L), p.getCounts());
        assertEquals(DAX_DIMS, p.getAttributes());
    }

    @Test
    void anEventIsCountedByTypeAndSeverityAndAVerdictByItsAction() {
        List<ServiceEvent> written = new ArrayList<>();
        EventLog log = metrics.counting(written::add);
        log.write(ServiceEvent.of(EventType.WATCHDOG_STALE, T0).forEpic(DAX)
                .withDetail(MAPPER.createObjectNode().put("action", "resubscribe").put("signal", "TICK_SILENT")));
        log.write(ServiceEvent.of(EventType.WATCHDOG_STALE, T0).forEpic(DAX)
                .withDetail(MAPPER.createObjectNode().put("action", "resubscribe")));
        log.write(ServiceEvent.of(EventType.WATCHDOG_STALE, T0)); // a session verdict: no detail, a rebuild
        log.write(ServiceEvent.of(EventType.RECONNECT, T0));

        var points = metric(reader.collectAllMetrics(), CollectorMetrics.EVENTS).getLongSumData().getPoints();

        assertEquals(4, written.size(), "every event still reaches the log");
        assertEquals(3, points.size(), "resubscribes on DAX, the rebuild, the reconnect");
        assertEquals(2, value(points, Attributes.builder().putAll(DAX_DIMS).put("type", "WATCHDOG_STALE")
                .put("severity", "WARN").put("action", "resubscribe").build()));
        assertEquals(1, value(points, Attributes.builder().put("user", "platform").put("source", "ig-stream-demo")
                .put("type", "WATCHDOG_STALE").put("severity", "WARN").put("action", "rebuild").build()));
        assertEquals(1, value(points, Attributes.builder().put("user", "platform").put("source", "ig-stream-demo")
                .put("type", "RECONNECT").put("severity", "WARN").build()));
    }

    @Test
    void aStreamGapAddsItsMinutesFromTheStream() {
        EventLog log = metrics.counting(e -> { });
        log.write(ServiceEvent.of(EventType.BAR_GAP, T0).forEpic(DAX).withDetail(MAPPER.createObjectNode().put("missingMinutes", 26)));
        log.write(ServiceEvent.of(EventType.BAR_GAP, T0).forEpic(DAX).withDetail(MAPPER.createObjectNode().put("missingMinutes", 17)));

        var points = metric(reader.collectAllMetrics(), CollectorMetrics.GAP_MINUTES).getLongSumData().getPoints();

        assertEquals(1, points.size());
        assertEquals(43, value(points, Attributes.builder().putAll(DAX_DIMS).put("origin", "stream").build()));
    }

    @Test
    void anEventIsCountedEvenWhenTheDatabaseRefusesIt() {
        EventLog refusing = metrics.counting(e -> {
            throw new IllegalStateException("database down");
        });
        try {
            refusing.write(ServiceEvent.of(EventType.SINK_FAILURE, T0));
        } catch (IllegalStateException expected) {
            // the best-effort wrapper above it says so; the event happened all the same
        }

        assertEquals(1, metric(reader.collectAllMetrics(), CollectorMetrics.EVENTS).getLongSumData().getPoints()
                .iterator().next().getValue());
    }

    @Test
    void theStoreTimesABarAndAFlushOnlyWhenTicksWereWritten() {
        boolean[] failNext = {false};
        CaptureStore slow = new CaptureStore() {
            @Override
            public void write(Bar1m bar) {
                now += 4_000_000; // 4 ms
            }

            @Override
            public void write(Tick tick) {
            }

            @Override
            public void flush() {
                now += 12_000_000; // 12 ms
                if (failNext[0]) {
                    failNext[0] = false;
                    throw new IllegalStateException("blip");
                }
            }

            @Override
            public boolean recover() {
                return false;
            }

            @Override
            public void close() {
            }
        };
        CaptureStore timed = metrics.timed(slow, () -> now);
        timed.write(new Bar1m(DAX, T0, ohlc(), ohlc(), null));
        timed.flush();            // nothing written since the last flush: a no-op, not timed
        timed.write(new Tick(DAX, T0, BigDecimal.ONE, BigDecimal.TWO));
        failNext[0] = true;
        try {
            timed.flush();        // fails: not timed, and the ticks are still pending
        } catch (IllegalStateException expected) {
        }
        timed.flush();            // lands: timed once
        timed.flush();            // nothing new: not timed

        var points = metric(reader.collectAllMetrics(), CollectorMetrics.STORE_WRITE_DURATION).getHistogramData().getPoints();

        HistogramPointData bar = point(points, "bar");
        HistogramPointData ticks = point(points, "ticks");
        assertEquals(1, bar.getCount());
        assertEquals(0.004, bar.getSum(), 1e-9);
        assertEquals(1, ticks.getCount(), "one landed flush — not the idle one, not the failed one");
        assertEquals(0.012, ticks.getSum(), 1e-9);
    }

    @Test
    void theMetricNamesAreTheDashboardsContract() {
        JobMetrics jobs = new JobMetrics(provider.get("test"), "platform", "ig-stream-demo");
        buffers.onTick(tick(DAX, 0, "1.0"));
        buffers.onTick(tick(DAX, 1, "2.0"));
        buffers.onSealedBar(bar(1));
        EventLog log = metrics.counting(e -> { });
        log.write(ServiceEvent.of(EventType.BAR_GAP, T0).forEpic(DAX).withDetail(MAPPER.createObjectNode().put("missingMinutes", 1)));
        CaptureStore timed = metrics.timed(new NoStore(), () -> now);
        timed.write(new Bar1m(DAX, T0, ohlc(), ohlc(), null));
        jobs.counting(new NoRuns()).record(new JobRun("heal", new MarketDay(DAX, java.time.LocalDate.of(2026, 10, 29)), T0,
                T0.plusSeconds(1), true, "{\"missing\":2,\"healedMinutes\":1,\"pointsSpent\":1,\"gapMinutesAdded\":1,\"allowanceRemaining\":9000}"));
        Set<String> names = new TreeSet<>();
        reader.collectAllMetrics().forEach(m -> names.add(m.getName()));

        // anchored on the nod of 2026-10-30, not on the constants: renaming one breaks deploy/grafana/
        assertEquals(new TreeSet<>(List.of("tradebench.capture.ticks", "tradebench.capture.bars",
                "tradebench.capture.ticks.dropped", "tradebench.capture.updates.malformed",
                "tradebench.capture.tick.age", "tradebench.capture.bar.age", "tradebench.capture.queue.pending",
                "tradebench.capture.tick.gap", "tradebench.events", "tradebench.gap.minutes",
                "tradebench.store.write.duration", "tradebench.jobs.runs", "tradebench.jobs.duration",
                "tradebench.heal.minutes", "tradebench.heal.points_spent", "tradebench.ig.allowance.remaining")), names);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    private static OhlcPrices ohlc() {
        return new OhlcPrices(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
    }

    private static long value(Collection<LongPointData> points, Attributes attributes) {
        return points.stream().filter(p -> p.getAttributes().equals(attributes)).findFirst()
                .orElseThrow(() -> new AssertionError("no series " + attributes + " in " + points)).getValue();
    }

    private static HistogramPointData point(Collection<HistogramPointData> points, String op) {
        return points.stream().filter(p -> op.equals(p.getAttributes().get(CollectorMetrics.OP))).findFirst()
                .orElseThrow(() -> new AssertionError("no op=" + op + " in " + points));
    }

    private static final class NoStore implements CaptureStore {
        @Override public void write(Bar1m bar) { }
        @Override public void write(Tick tick) { }
        @Override public void flush() { }
        @Override public boolean recover() { return false; }
        @Override public void close() { }
    }

    private static final class NoRuns implements JobRunStore {
        @Override public List<JobRun> runs(String job, MarketDay day) { return List.of(); }
        @Override public void record(JobRun run) { }
    }
}
