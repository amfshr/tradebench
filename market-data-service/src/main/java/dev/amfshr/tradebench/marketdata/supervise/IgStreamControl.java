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

    /** A market's pair, leg by leg: a leg is forgotten the moment its own unsubscribe succeeds, so
     * a refused twin never leaves an inactive handle to be re-passed to the SDK (E1-T10 #15). */
    private static final class Pair {
        StreamTransport.@Nullable SubscriptionHandle price;
        StreamTransport.@Nullable SubscriptionHandle chart;

        Pair(StreamTransport.SubscriptionHandle price, StreamTransport.SubscriptionHandle chart) {
            this.price = price;
            this.chart = chart;
        }
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
    private final Map<String, Pair> legs = new HashMap<>();
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
            } catch (RuntimeException e) {
                log.accept("FATAL: the stream could not be set up at boot — not retrying: " + e);
                throw e; // like rebuild()'s arm, but boot fails loud: nothing is left half-built (#17)
            }
        }
    }

    @Override
    public Outcome rebuild() {
        closeStream();
        try {
            connect(sessions.afterFailure());
            return Outcome.CONNECTED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.accept("rebuild interrupted — stream left down");
        } catch (IgFatalConfigException e) {
            log.accept("FATAL: IG rejected the configuration — recovery is pointless: "
                    + e.getMessage());
            return Outcome.FATAL;
        } catch (IOException | RuntimeException e) {
            log.accept("rebuild failed — stream left down for the backoff ladder: " + e);
        }
        return Outcome.FAILED;
    }

    @Override
    public int generation() {
        return generation.get();
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

    /** Connect and subscribe every market; the stream is published only once whole — a subscribe
     * that throws closes the half-built connection and leaves nothing live-but-unknown (#17). */
    private void connect(IgSession session) {
        int gen = generation.get();
        IgStreamSession live = client.connect(session, events, gated(gen));
        Map<String, Pair> subscribed = new HashMap<>();
        try {
            for (String epic : epics) {
                if (!quarantined.contains(epic)) {
                    subscribed.put(epic, subscribePair(live, epic, gen));
                }
            }
        } catch (RuntimeException e) {
            generation.incrementAndGet(); // its farewell is nobody's
            try {
                live.close();
            } catch (RuntimeException closing) {
                log.accept("half-built stream close failed: " + closing);
            }
            throw e;
        }
        stream = live;
        legs.putAll(subscribed);
        log.accept("stream connected: account " + session.activeAccountId() + " via "
                + session.lightstreamerEndpoint());
    }

    /** Both legs or neither: a CHART subscribe that throws rolls the PRICE leg back, so no handle
     * streams without an owner and the next attempt starts clean (#16). */
    private Pair subscribePair(IgStreamSession live, String epic, int gen) {
        StreamTransport.SubscriptionHandle price =
                live.subscribePrice(epic, leg(epic, WitnessQuarantine.Kind.PRICE, gen));
        try {
            return new Pair(price, live.subscribeChart1m(epic, leg(epic, WitnessQuarantine.Kind.CHART, gen)));
        } catch (RuntimeException e) {
            try {
                live.unsubscribe(price);
            } catch (RuntimeException rollback) {
                log.accept("rollback of " + epic + " PRICE refused — a leg streams unowned: " + rollback);
            }
            throw e;
        }
    }

    /** Drop a market's pair, leg by leg: each leg is forgotten as its own unsubscribe succeeds, a
     * refused one is kept for the next attempt — so nothing live is ever forgotten, and nothing
     * already gone is ever re-passed to the SDK (#15). The first refusal is rethrown. */
    private void dropLegs(IgStreamSession live, String epic) {
        Pair pair = legs.get(epic);
        if (pair == null) {
            return;
        }
        @Nullable RuntimeException refused = null;
        if (pair.price != null) {
            try {
                live.unsubscribe(pair.price);
                pair.price = null;
            } catch (RuntimeException e) {
                refused = e;
            }
        }
        if (pair.chart != null) {
            try {
                live.unsubscribe(pair.chart);
                pair.chart = null;
            } catch (RuntimeException e) {
                refused = refused == null ? e : refused;
            }
        }
        if (pair.price == null && pair.chart == null) {
            legs.remove(epic);
        }
        if (refused != null) {
            throw refused;
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
                    target.onStatusChange(gen, status);
                }
            }

            @Override
            public void onServerError(int code, String message) {
                if (live(gen)) {
                    target.onServerError(gen, code, message);
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
                    target.onSubscribed(gen, epic, kind);
                }
            }

            @Override
            public void onSubscriptionError(int code, String message) {
                if (live(gen)) {
                    target.onSubscriptionError(gen, epic, kind, code, message);
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
