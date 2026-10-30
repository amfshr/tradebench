package dev.amfshr.tradebench.marketdata.metrics;

import static io.opentelemetry.api.common.AttributeKey.stringKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;

import dev.amfshr.tradebench.marketdata.ingest.Buffers;
import dev.amfshr.tradebench.marketdata.supervise.MarketTelemetry;
import dev.amfshr.tradebench.marketdata.supervise.MarketTelemetry.MarketCounts;

/**
 * The collector's capture metrics (E1-T16, the nod of 2026-10-30), through the OpenTelemetry API: the
 * agent supplies the SDK in production; without it the API is a no-op. The names below are a contract
 * with the dashboards in {@code deploy/grafana/} — Grafana sees them translated, {@code .} to {@code _},
 * counters suffixed {@code _total}, seconds {@code _seconds}. Counts and ages are read at export from the
 * ingest buffer's own telemetry, so the hot path gains nothing; the tick-gap histogram alone records on
 * the LS thread, one cheap call per tick that is news. Every series carries market, user and source.
 */
public final class CollectorMetrics {

    public static final String TICKS = "tradebench.capture.ticks";
    public static final String BARS = "tradebench.capture.bars";
    public static final String TICKS_DROPPED = "tradebench.capture.ticks.dropped";
    public static final String UPDATES_MALFORMED = "tradebench.capture.updates.malformed";
    public static final String TICK_AGE = "tradebench.capture.tick.age";
    public static final String BAR_AGE = "tradebench.capture.bar.age";
    public static final String QUEUE_PENDING = "tradebench.capture.queue.pending";
    public static final String TICK_GAP = "tradebench.capture.tick.gap";
    /** Seconds; from a busy second's ticks to a five-minute silence (B24's question is the tail). */
    public static final List<Double> TICK_GAP_BUCKETS =
            List.of(0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0, 90.0, 120.0, 300.0);

    public static final AttributeKey<String> MARKET = stringKey("market");
    public static final AttributeKey<String> USER = stringKey("user");
    public static final AttributeKey<String> SOURCE = stringKey("source");

    private final Meter meter;
    private final Map<String, Attributes> byMarket = new LinkedHashMap<>();
    private final Attributes dimensions;
    private final DoubleHistogram tickGap;
    private final List<AutoCloseable> observers = new ArrayList<>();

    /** @param epics the markets watched — their attribute sets are built once, here */
    public CollectorMetrics(Meter meter, String user, String source, List<String> epics) {
        this.meter = meter;
        this.dimensions = Attributes.of(USER, user, SOURCE, source);
        for (String epic : epics) {
            byMarket.put(epic, dimensions.toBuilder().put(MARKET, epic).build());
        }
        this.tickGap = meter.histogramBuilder(TICK_GAP).setUnit("s")
                .setDescription("Seconds of IG time between a market's consecutive ticks that are news")
                .setExplicitBucketBoundariesAdvice(TICK_GAP_BUCKETS).build();
    }

    /** The buffer's hook: one histogram record per tick that is news, for a watched market. */
    public Buffers.TickGaps tickGaps() {
        return (epic, seconds) -> {
            Attributes attributes = byMarket.get(epic);
            if (attributes != null) {
                tickGap.record(seconds, attributes);
            }
        };
    }

    /** The read-at-export instruments, over the buffer's telemetry and the monotonic clock. */
    public void observe(MarketTelemetry telemetry, LongSupplier monotonicNanos) {
        counter(TICKS, "{tick}", "Ticks that were news, per market", telemetry, MarketCounts::ticks);
        counter(BARS, "{bar}", "Sealed one-minute bars, per market", telemetry, MarketCounts::bars);
        counter(TICKS_DROPPED, "{tick}", "Ticks shed by the bounded queue", telemetry, MarketCounts::dropped);
        counter(UPDATES_MALFORMED, "{update}", "Price updates that failed to parse", telemetry,
                MarketCounts::malformed);
        observers.add(meter.gaugeBuilder(TICK_AGE).setUnit("s")
                .setDescription("Seconds since the market's last tick that was news arrived").buildWithCallback(m -> {
                    long now = monotonicNanos.getAsLong();
                    byMarket.forEach((epic, attributes) -> {
                        long last = telemetry.lastTickMono(epic);
                        if (last != Long.MIN_VALUE) {
                            m.record((now - last) / 1e9, attributes);
                        }
                    });
                }));
        observers.add(meter.gaugeBuilder(BAR_AGE).setUnit("s")
                .setDescription("Seconds since the market's last sealed bar arrived").buildWithCallback(m -> {
                    long now = monotonicNanos.getAsLong();
                    byMarket.forEach((epic, attributes) -> {
                        long last = telemetry.lastBarMono(epic);
                        if (last != Long.MIN_VALUE) {
                            m.record((now - last) / 1e9, attributes);
                        }
                    });
                }));
        observers.add(meter.gaugeBuilder(QUEUE_PENDING).ofLongs().setUnit("{update}")
                .setDescription("Updates queued for the pump and not yet written")
                .buildWithCallback(m -> m.record(telemetry.pendingWrites(), dimensions)));
    }

    private void counter(String name, String unit, String description, MarketTelemetry telemetry,
            ToLongFunction<MarketCounts> count) {
        observers.add(meter.counterBuilder(name).setUnit(unit).setDescription(description).buildWithCallback(m ->
                byMarket.forEach((epic, attributes) -> m.record(count.applyAsLong(telemetry.countsFor(epic)), attributes))));
    }
}
