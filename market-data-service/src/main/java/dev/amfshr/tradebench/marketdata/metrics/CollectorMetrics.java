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
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;

import com.fasterxml.jackson.databind.JsonNode;

import dev.amfshr.tradebench.core.domain.Bar1m;
import dev.amfshr.tradebench.core.domain.Tick;
import dev.amfshr.tradebench.marketdata.events.EventType;
import dev.amfshr.tradebench.marketdata.ingest.Buffers;
import dev.amfshr.tradebench.marketdata.store.CaptureStore;
import dev.amfshr.tradebench.marketdata.store.EventLog;
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
    public static final String EVENTS = "tradebench.events";
    public static final String GAP_MINUTES = "tradebench.gap.minutes";
    public static final String STORE_WRITE_DURATION = "tradebench.store.write.duration";
    public static final String JOB_RUNS = "tradebench.jobs.runs";
    public static final String JOB_DURATION = "tradebench.jobs.duration";
    public static final String HEAL_MINUTES = "tradebench.heal.minutes";
    public static final String HEAL_POINTS_SPENT = "tradebench.heal.points_spent";
    public static final String IG_ALLOWANCE_REMAINING = "tradebench.ig.allowance.remaining";
    /** Seconds; from a busy second's ticks to a five-minute silence (B24's question is the tail). */
    public static final List<Double> TICK_GAP_BUCKETS =
            List.of(0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0, 90.0, 120.0, 300.0);

    public static final AttributeKey<String> MARKET = stringKey("market");
    public static final AttributeKey<String> USER = stringKey("user");
    public static final AttributeKey<String> SOURCE = stringKey("source");
    public static final AttributeKey<String> TYPE = stringKey("type");
    public static final AttributeKey<String> SEVERITY = stringKey("severity");
    public static final AttributeKey<String> ACTION = stringKey("action");
    public static final AttributeKey<String> ORIGIN = stringKey("origin");
    public static final AttributeKey<String> OP = stringKey("op");
    /** Not {@code job}: Prometheus already uses {@code job} for the service, {@code job="market-data-service"}. */
    public static final AttributeKey<String> JOB = stringKey("job_name");
    public static final AttributeKey<String> OUTCOME = stringKey("outcome");

    private final Meter meter;
    private final Map<String, Attributes> byMarket = new LinkedHashMap<>();
    private final Attributes dimensions;
    private final DoubleHistogram tickGap;
    private final LongCounter events;
    private final LongCounter gapMinutes;
    private final DoubleHistogram storeWrite;
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
        this.events = meter.counterBuilder(EVENTS).setUnit("{event}")
                .setDescription("Service events recorded, by type and severity").build();
        this.gapMinutes = gapMinutes(meter);
        this.storeWrite = meter.histogramBuilder(STORE_WRITE_DURATION).setUnit("s")
                .setDescription("Seconds a database write took: a bar, or a batch of ticks").build();
    }

    /** One instrument whichever side counts: the stream's detector here, the heal in {@link JobMetrics}. */
    static LongCounter gapMinutes(Meter meter) {
        return meter.counterBuilder(GAP_MINUTES).setUnit("min")
                .setDescription("Minutes found missing, by origin — the stream's detector or the heal; never summed across").build();
    }

    /** Counts every event on its way to {@code delegate} — before the write, so a refused row is still
     * counted: the event happened. A stream gap also adds its minutes, origin {@code stream}. */
    public EventLog counting(EventLog delegate) {
        return event -> {
            AttributesBuilder attributes = (event.epic() == null ? dimensions : market(event.epic())).toBuilder()
                    .put(TYPE, event.type().name()).put(SEVERITY, event.severity().name());
            JsonNode detail = event.detail();
            if (event.type() == EventType.WATCHDOG_STALE) {
                attributes.put(ACTION, detail != null && detail.hasNonNull("action") ? detail.get("action").asText() : "rebuild");
            }
            events.add(1, attributes.build());
            if (event.type() == EventType.BAR_GAP && event.epic() != null && detail != null && detail.has("missingMinutes")) {
                gapMinutes.add(detail.get("missingMinutes").asLong(), market(event.epic()).toBuilder().put(ORIGIN, "stream").build());
            }
            delegate.write(event);
        };
    }

    /** Times the sink's real database round trips — a bar's write, and a flush only when ticks were
     * written since the last one (the pump's idle flush is a no-op, and would bury the real ones). */
    public CaptureStore timed(CaptureStore delegate, LongSupplier monotonicNanos) {
        Attributes bars = dimensions.toBuilder().put(OP, "bar").build();
        Attributes ticks = dimensions.toBuilder().put(OP, "ticks").build();
        return new CaptureStore() {
            private int ticksSinceFlush;

            @Override
            public void write(Bar1m bar) {
                long started = monotonicNanos.getAsLong();
                delegate.write(bar);
                storeWrite.record((monotonicNanos.getAsLong() - started) / 1e9, bars);
            }

            @Override
            public void write(Tick tick) {
                delegate.write(tick);
                ticksSinceFlush++;
            }

            @Override
            public void flush() {
                if (ticksSinceFlush == 0) {
                    delegate.flush();
                    return;
                }
                long started = monotonicNanos.getAsLong();
                delegate.flush();
                storeWrite.record((monotonicNanos.getAsLong() - started) / 1e9, ticks);
                ticksSinceFlush = 0;
            }

            @Override
            public boolean recover() {
                return delegate.recover();
            }

            @Override
            public void close() {
                delegate.close();
            }
        };
    }

    private Attributes market(String epic) {
        Attributes known = byMarket.get(epic);
        return known != null ? known : dimensions.toBuilder().put(MARKET, epic).build();
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
