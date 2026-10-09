package dev.amfshr.tradebench.marketdata.scenario;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import dev.amfshr.tradebench.ig.stream.FakeStreamTransport;
import dev.amfshr.tradebench.ig.stream.Ohlc;
import dev.amfshr.tradebench.ig.stream.StreamTransport;
import dev.amfshr.tradebench.marketdata.app.CaptureAssembly;
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

/**
 * Drives the production composition ({@link CaptureAssembly}) through a {@link Scenario} with no
 * threads and no real waiting. Time is the fake clock's and passes only when the pump sleeps —
 * an idle wait, or a hold during a database outage — so between sleeps the pump cycles as fast as
 * it would on a real thread, until it has nothing left to drain. Each time the clock moves, the
 * same hook gives the other threads their turn in one deterministic order: the events now due go
 * onto the fake wire (enqueue-on-the-callback-thread), then every sweep that fell due (apply-on-
 * the-next-sweep), then every heartbeat. An exit the shell asks for — recovery exhausted, the pump
 * dead — runs the shutdown order exactly as the JVM's exit hook would, once the step that asked
 * for it has returned.
 */
public final class ScenarioRunner {

    /** Cycles the pump may take at one instant before the harness calls it a runaway. */
    private static final int BUSY_LIMIT = 100_000;

    /** The instrument failed — a fixture that cannot be applied, a guard tripped, a sweep that
     * threw. Never a verdict on the system under test: it ends the run as an error, not an exit. */
    public static final class HarnessError extends RuntimeException {
        HarnessError(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final Scenario scenario;
    private final FakeClock clock;
    private final FakeSleeper sleeper;
    private final FakeStreamTransport transport = new FakeStreamTransport();
    private final FakeSessions sessions = new FakeSessions();
    private final ScriptedCaptureStore sink = new ScriptedCaptureStore();
    private final RecordingEventLog events = new RecordingEventLog();
    private final RecordingGapStore gaps = new RecordingGapStore();
    private final RecordingStatusStore status = new RecordingStatusStore();
    private final Observed observed = new Observed();
    private final CaptureAssembly capture;
    private final Instant start;

    private Duration nextSweep = Duration.ZERO;
    private Duration nextHeartbeat = CaptureAssembly.HEARTBEAT;
    private int delivered;
    private boolean inHook;
    private boolean stopped;
    private boolean pumpDead;
    private @Nullable String exitAsked;
    private int connectionsSeen;
    private final Set<StreamTransport.SubscriptionHandle> handlesSeen = new HashSet<>();
    private final Set<StreamTransport.SubscriptionHandle> inactiveRecorded = new HashSet<>();
    private final Set<FakeStreamTransport.FakeConnection> closedRecorded = new HashSet<>();

    public static Observed run(Scenario scenario) throws InterruptedException {
        return new ScenarioRunner(scenario).run();
    }

    private ScenarioRunner(Scenario scenario) {
        this.scenario = scenario;
        this.clock = new FakeClock(scenario.origin != null ? scenario.origin : FakeClock.DEFAULT_START);
        this.sleeper = new FakeSleeper(clock);
        // one idle wait is 250ms; a scenario that stands down for twelve hours sleeps 170 000 times
        sleeper.maxSleeps = (int) Math.max(FakeSleeper.RUNAWAY, scenario.until.toMillis() / 50);
        this.start = clock.wallInstant();
        capture = CaptureAssembly.compose(new CaptureAssembly.Ports("scenario", scenario.epics,
                clock, sleeper, () -> 1.0, Tuning.playbook(), sessions, transport, sink, events, gaps,
                status, observed.log::add,
                () -> exitAsked = "FEED_DEAD → exit(1)",
                cause -> exitAsked = "pump died → exit(1): " + cause,
                Buffers.DEFAULT_TICK_CAPACITY));
        sleeper.onSleep = this::onTimePassed;
    }

    private Observed run() throws InterruptedException {
        capture.boot();
        observeWire();
        int busy = 0;
        while (!stopped && now().compareTo(scenario.until) < 0) {
            onTimePassed();
            if (stopped) {
                break;
            }
            long before = clock.monotonicNanos();
            try {
                capture.pump.cycle();
            } catch (HarnessError e) {
                throw e; // the instrument failed, not the pump — never laundered into a death
            } catch (RuntimeException e) {
                pumpDead = true;
                capture.pump.die(e); // Pump.run()'s catch, in its place
            }
            exitIfAsked();
            if (clock.monotonicNanos() == before) {
                if (++busy > BUSY_LIMIT) {
                    throw new IllegalStateException("the pump never idled at " + now());
                }
            } else {
                busy = 0;
            }
        }
        return finish();
    }

    /** The other threads' turn: deliver what is due, then every sweep and heartbeat now due. */
    private void onTimePassed() {
        if (inHook) {
            return;
        }
        inHook = true;
        try {
            if (deliverDue()) {
                clock.advance(Duration.ofNanos(1)); // a callback's stamp precedes the sweep that reads it
            }
            while (!stopped && now().compareTo(nextSweep) >= 0) {
                try {
                    capture.supervisor.sweep();
                } catch (RuntimeException e) {
                    capture.supervisor.die(e); // Supervisor.run()'s catch, in its place
                }
                observeWire();
                nextSweep = nextSweep.plus(CaptureAssembly.SWEEP_INTERVAL);
                exitIfAsked();
            }
            while (!stopped && now().compareTo(nextHeartbeat) >= 0) {
                capture.probe.publish();
                for (String epic : scenario.epics) {
                    var row = capture.probe.snapshot(epic);
                    observed.heartbeats.add(new Observed.Heartbeat(nextHeartbeat, epic,
                            row.streamState(), row.dbPending()));
                }
                nextHeartbeat = nextHeartbeat.plus(CaptureAssembly.HEARTBEAT);
            }
        } finally {
            inHook = false;
        }
    }

    /** Delivers every event now due; true if there was one. A fixture that cannot be applied is
     * a harness error, never a fact about the system under test. */
    private boolean deliverDue() {
        boolean any = false;
        while (!stopped && delivered < scenario.steps.size()
                && scenario.steps.get(delivered).at().compareTo(now()) <= 0
                && scenario.steps.get(delivered).at().compareTo(scenario.until) < 0) {
            Scenario.Step step = scenario.steps.get(delivered++);
            try {
                apply(step.event());
            } catch (HarnessError e) {
                throw e;
            } catch (RuntimeException e) {
                throw new HarnessError("the fixture could not be applied at " + step.at() + ": " + step.event(), e);
            }
            any = true;
        }
        return any;
    }

    private void apply(Event event) {
        FakeStreamTransport.FakeConnection wire = transport.last();
        switch (event) {
            case Event.Status(String s) -> wire.listener.onStatusChange(s);
            case Event.ServerError(int code, String message) -> wire.listener.onServerError(code, message);
            case Event.Confirm(String epic, Event.Leg leg) -> wire.confirm(item(epic, leg));
            case Event.Reject(String epic, Event.Leg leg, int code, String message) ->
                    wire.reject(item(epic, leg), code, message);
            case Event.Tick(String epic, String bid, String ask, String flag, Instant tsUtc) -> {
                Instant stamp = tsUtc != null ? tsUtc : clock.wallInstant();
                wire.deliver(item(epic, Event.Leg.PRICE),
                        FakeStreamTransport.Wire.tick(stamp.toEpochMilli(), bid, ask, flag));
                observed.ticksDelivered++;
            }
            case Event.Bar(String epic, Event.Quote bid, Event.Quote ask, Instant startUtc, Long ltv) -> {
                Instant start = startUtc != null ? startUtc
                        : clock.wallInstant().truncatedTo(ChronoUnit.MINUTES).minus(Duration.ofMinutes(1));
                wire.deliver(item(epic, Event.Leg.CHART),
                        FakeStreamTransport.Wire.sealedBar(start.toEpochMilli(), ohlc(bid), ohlc(ask), ltv));
                observed.barsDelivered++;
            }
            case Event.DbDown() -> {
                sink.down = true;
                events.failWrites = true;
                gaps.down = true;
                status.down = true;
            }
            case Event.DbUp() -> {
                sink.down = false;
                sink.rejectWrites = false;
                events.failWrites = false;
                gaps.down = false;
                status.down = false;
            }
            case Event.DbBroken() -> sink.terminal = true;
            case Event.DbWritesRefused() -> sink.rejectWrites = true;
            case Event.HostSleep(Duration by) -> clock.advanceWallOnly(by);
            case Event.IgDown() -> {
                transport.refuseConnects = true;
                sessions.unreachable = true;
            }
            case Event.IgUp() -> {
                transport.refuseConnects = false;
                sessions.unreachable = false;
            }
            case Event.Stop() -> shutdown("stopped");
        }
    }

    /** The JVM's exit hook, run once the sweep or cycle that asked to exit has returned. */
    private void exitIfAsked() {
        if (exitAsked != null && !stopped) {
            shutdown(exitAsked);
        }
    }

    /** {@code Main}'s shutdown hook: the pump's tail runs here in its thread's place — unless the
     * pump is already dead, when {@code run()} would have returned without one. */
    private void shutdown(String how) {
        capture.shutdown(() -> true, () -> {
            if (!pumpDead) {
                capture.pump.drainTail();
            }
            return true;
        });
        observeWire();
        observed.exits.add(new Observed.Exit(now(), how));
        stopped = true;
    }

    /** What the wire saw since last time: a new connection, new subscriptions, dropped ones —
     * and, when the scenario says the server answers, the healthy answer to each new thing. */
    private void observeWire() {
        List<FakeStreamTransport.FakeConnection> connections = transport.connections;
        for (int i = connectionsSeen; i < connections.size(); i++) {
            observed.remedies.add(new Observed.Remedy(now(), "connect", null));
            if (scenario.serverAnswers) {
                connections.get(i).listener.onStatusChange(Events.STREAMING);
            }
        }
        connectionsSeen = connections.size();
        for (FakeStreamTransport.FakeConnection connection : connections) {
            if (connection.closed && closedRecorded.add(connection)) {
                observed.remedies.add(new Observed.Remedy(now(), "disconnect", null));
            }
            for (FakeStreamTransport.Subscribed s : connection.subscriptions) {
                if (handlesSeen.add(s.handle())) {
                    String item = s.spec().items().get(0);
                    observed.remedies.add(new Observed.Remedy(now(), "subscribe", item));
                    Integer refusal = scenario.serverRejects.entrySet().stream()
                            .filter(e -> item.contains(e.getKey())).map(Map.Entry::getValue).findFirst().orElse(null);
                    if (refusal != null) {
                        connection.reject(item, refusal, "rejected");
                    } else if (scenario.serverAnswers) {
                        connection.confirm(item);
                    }
                }
                if (!s.handle().active() && inactiveRecorded.add(s.handle())) {
                    observed.remedies.add(new Observed.Remedy(now(), "unsubscribe", s.spec().items().get(0)));
                }
            }
        }
    }

    private Observed finish() {
        observeWire();
        for (ServiceEvent event : events.written) {
            observed.events.add(new Observed.Seen(Duration.between(start, event.eventTimeUtc()),
                    event.type(), event.epic(), event.detail()));
        }
        observed.landed.addAll(sink.landed);
        observed.statusFailures = capture.probe.statusFailures();
        observed.sinkRecoveries = sink.recoveries;
        observed.ticksHeldAtEnd = sink.held();
        observed.summary = capture.summary();
        return observed;
    }

    private static Ohlc ohlc(Event.Quote q) {
        return new Ohlc(new BigDecimal(q.open()), new BigDecimal(q.high()), new BigDecimal(q.low()), new BigDecimal(q.close()));
    }

    private Duration now() {
        return Duration.ofNanos(clock.monotonicNanos());
    }

    private static String item(String epic, Event.Leg leg) {
        return leg == Event.Leg.PRICE ? "PRICE:" + FakeSessions.ACCOUNT + ":" + epic
                : "CHART:" + epic + ":1MINUTE";
    }
}
