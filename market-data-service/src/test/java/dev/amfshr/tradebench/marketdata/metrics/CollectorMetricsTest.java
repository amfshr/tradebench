package dev.amfshr.tradebench.marketdata.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;

import dev.amfshr.tradebench.ig.stream.Ohlc;
import dev.amfshr.tradebench.ig.stream.SealedBarUpdate;
import dev.amfshr.tradebench.ig.stream.TickUpdate;
import dev.amfshr.tradebench.marketdata.ingest.Buffers;

/** What the collector records, read back from an in-memory OpenTelemetry SDK (E1-T16). */
class CollectorMetricsTest {

    private static final String DAX = "IX.D.DAX.DAILY.IP";
    private static final String NDX = "IX.D.NASDAQ.CASH.IP";
    private static final Instant T0 = Instant.parse("2026-10-30T08:00:00Z");
    private static final Attributes DAX_DIMS = Attributes.builder().put("market", DAX).put("user", "platform")
            .put("source", "ig-stream-demo").build();

    private InMemoryMetricReader reader;
    private long now; // the monotonic clock the buffer and the gauges read
    private Buffers buffers;

    @BeforeEach
    void setUp() {
        reader = InMemoryMetricReader.create();
        SdkMeterProvider provider = SdkMeterProvider.builder().registerMetricReader(reader).build();
        CollectorMetrics metrics = new CollectorMetrics(provider.get("test"), "platform", "ig-stream-demo", List.of(DAX));
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
    void theMetricNamesAreTheDashboardsContract() {
        buffers.onTick(tick(DAX, 0, "1.0"));
        buffers.onTick(tick(DAX, 1, "2.0"));
        buffers.onSealedBar(bar(1));
        Set<String> names = new TreeSet<>();
        reader.collectAllMetrics().forEach(m -> names.add(m.getName()));

        // anchored on the nod of 2026-10-30, not on the constants: renaming one breaks deploy/grafana/
        assertEquals(new TreeSet<>(List.of("tradebench.capture.ticks", "tradebench.capture.bars",
                "tradebench.capture.ticks.dropped", "tradebench.capture.updates.malformed",
                "tradebench.capture.tick.age", "tradebench.capture.bar.age", "tradebench.capture.queue.pending",
                "tradebench.capture.tick.gap")), names);
    }
}
