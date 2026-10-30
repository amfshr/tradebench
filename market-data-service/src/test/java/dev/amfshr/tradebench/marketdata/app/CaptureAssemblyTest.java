package dev.amfshr.tradebench.marketdata.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;

import dev.amfshr.tradebench.ig.stream.FakeStreamTransport;
import dev.amfshr.tradebench.ig.stream.TickUpdate;
import dev.amfshr.tradebench.marketdata.events.EventType;
import dev.amfshr.tradebench.marketdata.events.ServiceEvent;
import dev.amfshr.tradebench.marketdata.ingest.Buffers;
import dev.amfshr.tradebench.marketdata.supervise.Tuning;
import dev.amfshr.tradebench.marketdata.testutil.FakeClock;
import dev.amfshr.tradebench.marketdata.testutil.FakeSessions;
import dev.amfshr.tradebench.marketdata.testutil.FakeSleeper;
import dev.amfshr.tradebench.marketdata.testutil.RecordingEventLog;
import dev.amfshr.tradebench.marketdata.testutil.RecordingGapStore;
import dev.amfshr.tradebench.marketdata.testutil.RecordingStatusStore;
import dev.amfshr.tradebench.marketdata.testutil.ScriptedCaptureStore;

/** The composition root's own promises — what the harness exercises but cannot see. */
class CaptureAssemblyTest {

    private static final String DAX = "IX.D.DAX.DAILY.IP";

    private final RecordingEventLog log = new RecordingEventLog();
    private final AtomicBoolean exhausted = new AtomicBoolean();

    private CaptureAssembly compose() {
        return compose(CaptureAssembly.Telemetry.none());
    }

    private CaptureAssembly compose(CaptureAssembly.Telemetry telemetry) {
        FakeClock clock = new FakeClock();
        return CaptureAssembly.compose(new CaptureAssembly.Ports("test", List.of(DAX), clock,
                new FakeSleeper(clock), () -> 1.0, Tuning.playbook(), new FakeSessions(),
                new FakeStreamTransport(), new ScriptedCaptureStore(), log, new RecordingGapStore(),
                new RecordingStatusStore(), message -> { }, () -> exhausted.set(true), cause -> { },
                Buffers.DEFAULT_TICK_CAPACITY, telemetry));
    }

    @Test
    void theSupervisorsEventsReachTheLogOnlyThroughTheWriter() {
        // D29 (3), E1-T10 #12: nothing the sweep records is written on the sweep thread. It is
        // queued, and lands when the writer takes its turn — here, when the test drains in its place.
        CaptureAssembly capture = compose();

        capture.supervisor.die(new IllegalStateException("a sweep that threw"));

        assertTrue(exhausted.get(), "the runner is told at once — the hand-over is not queued");
        assertTrue(log.written.isEmpty(), "but the row is: nothing is written on the sweep thread");
        assertEquals(1, capture.events.queued());
        assertEquals(1, capture.events.drainOnce());
        assertEquals(EventType.FEED_DEAD, log.written.get(0).type());
    }

    @Test
    void theHeartbeatCountsThePumpsRefusedEventsAsObsFailures() throws InterruptedException {
        // E1-T10 #26: the pump's log is best-effort by type; what it swallows must still reach the
        // heartbeat under D27's word for the pump's Tier-2 failures.
        CaptureAssembly capture = compose();
        log.failWrites = true;
        capture.queues.onTick(new TickUpdate(DAX, Instant.parse("2026-09-28T09:00:00Z"),
                BigDecimal.ONE, BigDecimal.TWO, "DEAL"));

        capture.pump.cycle(); // the tick lands; its DEAL flag is a state change whose row is refused

        assertTrue(capture.summary().contains(" obsFailures=1 "), capture.summary());
        assertNull(capture.pump.failure(), "a breadcrumb never halts capture");
    }

    @Test
    void theHeartbeatCountsTheBeltsRefusedEventsAsEventWriteFailures() {
        // A full queue refuses the sweep's event (D29 (3)); the belt's decorator counts it, and the
        // heartbeat sums it with the writer's own refusals.
        CaptureAssembly capture = compose();
        for (int i = 0; i < CaptureAssembly.EVENT_QUEUE_CAPACITY; i++) {
            capture.events.write(ServiceEvent.of(EventType.RECONNECT, Instant.EPOCH)); // the writer never ran
        }

        capture.supervisor.die(new IllegalStateException("a sweep that threw")); // FEED_DEAD meets a full queue

        assertTrue(capture.summary().contains(" eventWriteFailures=1 "), capture.summary());
    }

    @Test
    void theMetricsSeeWhatTheComposedPipelineDoes() throws InterruptedException {
        // E1-T16: each decorator is tested alone in metrics/; this pins that the assembly puts them in the path
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        SdkMeterProvider provider = SdkMeterProvider.builder().registerMetricReader(reader).build();
        CaptureAssembly capture = compose(new CaptureAssembly.Telemetry(provider.get("test"), "platform", "ig-stream-demo"));
        capture.queues.onTick(new TickUpdate(DAX, Instant.parse("2026-09-28T09:00:00Z"),
                BigDecimal.ONE, BigDecimal.TWO, "DEAL"));
        capture.queues.onTick(new TickUpdate(DAX, Instant.parse("2026-09-28T09:00:02Z"),
                BigDecimal.TWO, BigDecimal.TEN, "DEAL"));

        capture.pump.cycle();                                            // drains the ticks into the sink's batch
        capture.pump.cycle();                                            // idle: the flush, through the timed sink
        capture.supervisor.die(new IllegalStateException("a sweep that threw")); // a belt event, still queued

        List<MetricData> all = List.copyOf(reader.collectAllMetrics());
        assertEquals(1, histogram(all, "tradebench.capture.tick.gap").getCount(), "the buffer's gap hook");
        assertEquals(2.0, histogram(all, "tradebench.capture.tick.gap").getSum(), 1e-9);
        assertEquals(1, histogram(all, "tradebench.store.write.duration").getCount(), "the pump's sink is the timed one");
        assertTrue(metric(all, "tradebench.events").getLongSumData().getPoints().stream()
                .anyMatch(p -> "FEED_DEAD".equals(p.getAttributes().get(AttributeKey.stringKey("type")))),
                "the belt's event is counted at the queue's door, before the writer runs");
    }

    private static MetricData metric(List<MetricData> all, String name) {
        return all.stream().filter(m -> m.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + name + " in " + all.stream().map(MetricData::getName).toList()));
    }

    private static HistogramPointData histogram(List<MetricData> all, String name) {
        return metric(all, name).getHistogramData().getPoints().iterator().next();
    }
}
