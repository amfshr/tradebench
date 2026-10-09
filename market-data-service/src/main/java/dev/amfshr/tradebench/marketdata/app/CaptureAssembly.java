package dev.amfshr.tradebench.marketdata.app;

import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

import dev.amfshr.tradebench.core.time.Clock;
import dev.amfshr.tradebench.ig.session.IgSessions;
import dev.amfshr.tradebench.ig.stream.IgStreamClient;
import dev.amfshr.tradebench.ig.stream.StreamTransport;
import dev.amfshr.tradebench.ig.time.Sleeper;
import dev.amfshr.tradebench.marketdata.coverage.GapDetector;
import dev.amfshr.tradebench.marketdata.ingest.Buffers;
import dev.amfshr.tradebench.marketdata.ingest.Pump;
import dev.amfshr.tradebench.marketdata.store.CaptureStore;
import dev.amfshr.tradebench.marketdata.store.EventLog;
import dev.amfshr.tradebench.marketdata.store.GapStore;
import dev.amfshr.tradebench.marketdata.store.StatusStore;
import dev.amfshr.tradebench.marketdata.supervise.BackoffPolicy;
import dev.amfshr.tradebench.marketdata.supervise.HealthProbe;
import dev.amfshr.tradebench.marketdata.supervise.IgStreamControl;
import dev.amfshr.tradebench.marketdata.supervise.Supervisor;
import dev.amfshr.tradebench.marketdata.supervise.Tuning;

/**
 * The capture pipeline's composition root — queues, pump, stream control, supervisor and health
 * probe wired exactly once, in one place, with the boot and shutdown <i>orders</i> that keep them
 * safe. {@link Main} composes it from the environment and gives it real threads, a real
 * Lightstreamer transport and real stores; the scenario harness (E1-T11) composes the same object
 * over fakes at the edges and drives {@link Supervisor#sweep()} and {@link Pump#cycle()} itself,
 * so what the harness exercises is the production wiring, not a copy that can drift.
 */
public final class CaptureAssembly {

    /** The supervisor's sweep cadence — an internal rhythm, not a tunable: the watchdog thresholds
     * are 90s/210s, so a 1s sweep is responsive and cheap. */
    public static final Duration SWEEP_INTERVAL = Duration.ofSeconds(1);
    /** The heartbeat cadence: {@code capture_status} rows and the summary line (ch. 10). */
    public static final Duration HEARTBEAT = Duration.ofSeconds(60);
    /** Between boot attempts while IG is merely unreachable — the login gate paces the logins
     * themselves; this keeps a restart during an outage from spinning. */
    public static final Duration BOOT_RETRY = Duration.ofSeconds(30);

    /** Everything the pipeline is composed from — the application supplies the real ones, a test
     * the fakes; nothing here is machine-specific. */
    public record Ports(String instance, List<String> epics, Clock clock, Sleeper sleeper,
            DoubleSupplier jitter, Tuning tuning, IgSessions sessions, StreamTransport transport,
            CaptureStore sink, EventLog events, GapStore gaps, StatusStore status,
            Consumer<String> log, Runnable onExhausted, Consumer<RuntimeException> onPumpDeath,
            int tickCapacity) {
    }

    public final Buffers queues;
    public final Pump pump;
    public final IgStreamControl control;
    public final Supervisor supervisor;
    public final HealthProbe probe;

    private final List<String> epics;
    private final CaptureStore sink;
    private final Consumer<String> log;

    public static CaptureAssembly compose(Ports ports) {
        return new CaptureAssembly(ports);
    }

    private CaptureAssembly(Ports p) {
        this.epics = List.copyOf(p.epics());
        this.sink = p.sink();
        this.log = p.log();
        queues = new Buffers(p.tickCapacity(), p.clock()::monotonicNanos);
        pump = new Pump(queues, p.sink(), new GapDetector(), p.gaps(), p.events(), p.sleeper(),
                p.clock(), new BackoffPolicy(p.tuning(), p.jitter()), p.log(), p.onPumpDeath());
        control = new IgStreamControl(p.sessions(), new IgStreamClient(p.transport()), queues, epics,
                p.log(), p.sleeper(), BOOT_RETRY, p.clock()::monotonicNanos, p.tuning().giveUpAfter());
        supervisor = new Supervisor(p.clock(), p.tuning(), p.jitter(), p.events(), control, queues,
                p.onExhausted(), p.sleeper(), SWEEP_INTERVAL);
        control.bind(supervisor); // the one back-edge of the control loop, tied off here
        probe = new HealthProbe(p.instance(), epics, queues, supervisor, p.clock(), p.status());
    }

    /** Boot, in order: connect the stream (bounded; fails loud), then watch every market. Threads
     * are the caller's: start them after this returns. */
    public void boot() throws InterruptedException {
        control.start();
        for (String epic : epics) {
            supervisor.watch(epic);
        }
    }

    /**
     * Shutdown, in order: stop the sweep and wait for it
     * (the sweep thread owns the stream's handles), close the stream, stop the pump and wait for
     * it, and only then close the sink — never a sink a pump might still be writing to. The two
     * waits are the caller's joins (a real thread's, or a harness running the pump's tail inline).
     * Returns whether the pump stopped in time, so the caller knows what else it may close.
     */
    public boolean shutdown(BooleanSupplier joinSweep, BooleanSupplier joinPump) {
        supervisor.stop();
        if (!joinSweep.getAsBoolean()) {
            log.accept("supervisor did not stop within 5s — closing the stream anyway");
        }
        control.close(); // only once the sweep thread is done — it owns the handles
        pump.stop();
        boolean pumpStopped = joinPump.getAsBoolean();
        if (pumpStopped) {
            try {
                sink.close();
            } catch (RuntimeException e) {
                log.accept("sink close failed: " + e);
            }
        } else {
            log.accept("pump did not stop within 5s — leaving sink open to avoid a close/write"
                    + " race; file may miss its tail");
        }
        log.accept("queued writes at shutdown: " + queues.pendingWrites());
        if (pump.failure() != null) {
            log.accept("pump failure at shutdown: " + pump.failure() + " — " + queues.pendingWrites()
                    + " queued writes and the sink's held batch were not written");
        }
        return pumpStopped;
    }

    /** The heartbeat line — the honest numbers, every minute. */
    public String summary() {
        return "ticks=" + queues.tickCount() + " bars=" + queues.barCount()
                + " written=" + pump.writtenCount()
                + " dropped=" + queues.droppedTicks() + " malformed=" + queues.malformedUpdates()
                + " sinkFailures=" + pump.sinkFailures()
                + " obsFailures=" + pump.observabilityFailures()
                + " eventWriteFailures=" + supervisor.eventWriteFailures()
                + " statusFailures=" + probe.statusFailures();
    }
}
