package dev.amfshr.tradebench.marketdata.supervise;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.jspecify.annotations.Nullable;

import dev.amfshr.tradebench.ig.error.IgFatalConfigException;
import dev.amfshr.tradebench.ig.error.IgRetryableException;
import dev.amfshr.tradebench.ig.session.IgSession;
import dev.amfshr.tradebench.ig.session.IgSessions;
import dev.amfshr.tradebench.ig.stream.IgStreamClient;
import dev.amfshr.tradebench.ig.stream.IgStreamSession;
import dev.amfshr.tradebench.ig.stream.StreamEvents;
import dev.amfshr.tradebench.ig.stream.StreamTransport;
import dev.amfshr.tradebench.ig.time.Sleeper;

/**
 * The real {@link StreamControl}: owns one collector's IG session + Lightstreamer stream
 * lifecycle — the boot connect and every rebuild take the same path. Each market streams its
 * PRICE+CHART pair (E1 doctrine: ticks for precision, broker 1m bars for the healable record),
 * every leg reporting to the bound {@link StreamObserver}. <b>Threading contract:</b>
 * {@link #start()} runs on the boot thread before the supervisor thread exists; the three
 * remedies run on the supervisor's sweep thread only — so the handle bookkeeping needs no
 * locking. {@code start()} retries while IG is merely unreachable — within a budget — and
 * fails loud on a rejected configuration or when that budget runs out; the remedies never
 * throw — a failed rebuild is logged and leaves the stream down for the Supervisor's backoff
 * ladder, and a rejected configuration reports {@code false} so the ladder stops (ruled
 * 2026-10-03). Every connection's callbacks are gated by generation: once it is superseded,
 * nothing it says reaches the observer — a late farewell cannot open a phantom outage, a dead
 * leg cannot strike the new session.
 */
public final class IgStreamControl implements StreamControl, AutoCloseable {

    private record Legs(StreamTransport.SubscriptionHandle price,
            StreamTransport.SubscriptionHandle chart) {
    }

    private final IgSessions sessions;
    private final IgStreamClient client;
    private final StreamEvents events;
    private final List<String> epics;
    private final Consumer<String> log;
    private final Sleeper sleeper;
    private final Duration bootRetry;
    private final LongSupplier monotonicNanos;
    private final Duration bootBudget;
    private final Map<String, Legs> legs = new HashMap<>();
    private final Set<String> quarantined = new HashSet<>();
    private final AtomicInteger generation = new AtomicInteger(); // bumped as a connection is superseded
    private @Nullable StreamObserver observer;
    private @Nullable IgStreamSession stream;

    public IgStreamControl(IgSessions sessions, IgStreamClient client, StreamEvents events,
            List<String> epics, Consumer<String> log, Sleeper sleeper, Duration bootRetry,
            LongSupplier monotonicNanos, Duration bootBudget) {
        this.sessions = sessions;
        this.client = client;
        this.events = events;
        this.epics = List.copyOf(epics);
        this.log = log;
        this.sleeper = sleeper;
        this.bootRetry = bootRetry;
        this.monotonicNanos = monotonicNanos;
        this.bootBudget = bootBudget;
    }

    /** Bind the observer every connect and subscribe reports to — once, before {@link #start()}.
     * The one back-edge of the control loop: the Supervisor drives this; this reports to it. */
    public void bind(StreamObserver observer) {
        this.observer = observer;
    }

    /** Boot: log in and connect, retrying while IG is merely unreachable (the login gate paces
     * the logins; {@code bootRetry} paces the attempts) so a restart during an outage never
     * becomes a login storm — but only for {@code bootBudget}: the error taxonomy defaults
     * unknown codes to retryable, so an unbounded loop would retry a permanent failure forever.
     * Past the budget, and for a configuration the broker rejects, boot fails loud at once. */
    public void start() throws InterruptedException {
        observer(); // a programming error must fail before a login is spent on it
        long since = monotonicNanos.getAsLong();
        for (int attempt = 1; ; attempt++) {
            try {
                connect(sessions.current());
                return;
            } catch (IgFatalConfigException e) {
                log.accept("FATAL: IG rejected the configuration at boot — not retrying: "
                        + e.getMessage());
                throw e;
            } catch (IgRetryableException | IOException e) {
                if (monotonicNanos.getAsLong() - since >= bootBudget.toNanos()) {
                    log.accept("FATAL: IG unreachable for " + bootBudget + " at boot — giving up"
                            + " for the process supervisor to restart: " + e);
                    throw new IllegalStateException("boot budget exhausted — IG unreachable for "
                            + bootBudget, e);
                }
                log.accept("boot attempt " + attempt + " — IG unreachable, retrying in "
                        + bootRetry + ": " + e);
                sleeper.sleep(bootRetry);
            }
        }
    }

    @Override
    public boolean rebuild() {
        closeStream();
        try {
            connect(sessions.afterFailure());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.accept("rebuild interrupted — stream left down");
        } catch (IgFatalConfigException e) {
            log.accept("FATAL: IG rejected the configuration — recovery is pointless: "
                    + e.getMessage());
            return false;
        } catch (IOException | RuntimeException e) {
            log.accept("rebuild failed — stream left down for the backoff ladder: " + e);
        }
        return true;
    }

    @Override
    public void resubscribe(String epic) {
        IgStreamSession live = stream;
        if (live == null) {
            log.accept("resubscribe " + epic + " skipped — no live stream");
            return;
        }
        if (quarantined.contains(epic)) {
            log.accept("resubscribe " + epic + " refused — quarantined (exit is restart-only)");
            return; // the second lock on the door: whoever asks, a quarantined pair never comes back
        }
        try {
            dropLegs(live, epic);
            legs.put(epic, subscribePair(live, epic, generation.get()));
        } catch (RuntimeException e) {
            log.accept("resubscribe " + epic + " failed: " + e);
        }
    }

    @Override
    public boolean quarantine(String epic) {
        // Excluded from every later rebuild whether or not the unsubscribe is accepted — the
        // Supervisor has already stopped watching it, and exit is restart-only (§3.5).
        quarantined.add(epic);
        IgStreamSession live = stream;
        if (live == null) {
            return true; // nothing is subscribed while the stream is down
        }
        try {
            dropLegs(live, epic);
            return true;
        } catch (RuntimeException e) {
            log.accept("quarantine " + epic + " — unsubscribe refused: " + e);
            return false;
        }
    }

    @Override
    public void close() {
        closeStream();
    }

    private void connect(IgSession session) {
        int gen = generation.get();
        IgStreamSession live = client.connect(session, events, gated(gen));
        stream = live;
        log.accept("stream connected: account " + session.activeAccountId() + " via "
                + session.lightstreamerEndpoint());
        for (String epic : epics) {
            if (!quarantined.contains(epic)) {
                legs.put(epic, subscribePair(live, epic, gen));
            }
        }
    }

    private Legs subscribePair(IgStreamSession live, String epic, int gen) {
        return new Legs(
                live.subscribePrice(epic, leg(epic, WitnessQuarantine.Kind.PRICE, gen)),
                live.subscribeChart1m(epic, leg(epic, WitnessQuarantine.Kind.CHART, gen)));
    }

    /** Drop a market's pair; the handles are forgotten only once both legs are gone, so a refused
     * unsubscribe leaves nothing live-but-unknown for the next attempt to double-subscribe. */
    private void dropLegs(IgStreamSession live, String epic) {
        Legs pair = legs.get(epic);
        if (pair != null) {
            live.unsubscribe(pair.price());
            live.unsubscribe(pair.chart());
            legs.remove(epic);
        }
    }

    /** The connection listener for generation {@code gen}: forwards only while that connection is
     * the live one (§3.6 — the farewell of a torn-down connection is not an outage). */
    private StreamTransport.ConnectionListener gated(int gen) {
        StreamObserver target = observer();
        return new StreamTransport.ConnectionListener() {
            @Override
            public void onStatusChange(String status) {
                if (live(gen)) {
                    target.onStatusChange(status);
                }
            }

            @Override
            public void onServerError(int code, String message) {
                if (live(gen)) {
                    target.onServerError(code, message);
                }
            }
        };
    }

    private StreamTransport.StateListener leg(String epic, WitnessQuarantine.Kind kind, int gen) {
        StreamObserver target = observer();
        return new StreamTransport.StateListener() {
            @Override
            public void onSubscribed() {
                if (live(gen)) {
                    target.onSubscribed(epic, kind);
                }
            }

            @Override
            public void onSubscriptionError(int code, String message) {
                if (live(gen)) {
                    target.onSubscriptionError(epic, kind, code, message);
                }
            }
        };
    }

    private boolean live(int gen) {
        return gen == generation.get();
    }

    private StreamObserver observer() {
        StreamObserver bound = observer;
        if (bound == null) {
            throw new IllegalStateException("IgStreamControl used before bind(observer)");
        }
        return bound;
    }

    private void closeStream() {
        generation.incrementAndGet(); // whatever the dying connection says from here on is stale
        IgStreamSession live = stream;
        stream = null;
        legs.clear();
        if (live != null) {
            try {
                live.close();
            } catch (RuntimeException e) {
                log.accept("stream close failed: " + e);
            }
        }
    }
}
