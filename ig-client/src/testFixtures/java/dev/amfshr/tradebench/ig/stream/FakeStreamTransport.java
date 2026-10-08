package dev.amfshr.tradebench.ig.stream;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The Lightstreamer seam, faked: records every connection and subscription so tests above the
 * transport (the stream client, the collection service's supervisor shell, the scenario harness)
 * assert on exactly what reached the wire, and can play the wire back — deliver an update to an
 * item, confirm or reject a subscription, refuse a connect or an unsubscribe. Handles carry
 * state as the SDK's do: unsubscribing an inactive one throws. The fake has no clock: <i>when</i>
 * something happens is the caller's (the scenario runner's) business. Shared via
 * {@code java-test-fixtures}.
 */
public final class FakeStreamTransport implements StreamTransport {

    /** Every connection ever opened, oldest first — a rebuild opens a new one. */
    public final List<FakeConnection> connections = new ArrayList<>();
    /** When set, the next {@link #connect} throws (and clears the flag). */
    public boolean failNextConnect;
    /** While set, every {@link #connect} throws — IG unreachable for as long as a test says. */
    public boolean refuseConnects;
    /** When set, every {@link Connection#unsubscribe} throws — the LS refusal case (§3.5). */
    public boolean refuseUnsubscribe;

    /** Identity, not value: two subscriptions to the same spec are two distinct handles, as on
     * the real transport — so dropping one never drops its twin. */
    public static final class FakeHandle implements SubscriptionHandle {
        private final SubscriptionSpec spec;
        private boolean active = true;

        FakeHandle(SubscriptionSpec spec) {
            this.spec = spec;
        }

        public SubscriptionSpec spec() {
            return spec;
        }

        /** False once unsubscribed — the SDK refuses a second unsubscribe. */
        public boolean active() {
            return active;
        }
    }

    /** One subscription as the wire sees it: its spec, its two listeners, its handle. */
    public record Subscribed(SubscriptionSpec spec, UpdateListener updates, StateListener state,
            FakeHandle handle) {
        public boolean carries(String item) {
            return spec.items().contains(item);
        }
    }

    public final class FakeConnection implements Connection {
        public final String serverAddress;
        public final String user;
        public final String password;
        public final ConnectionListener listener;
        public final List<SubscriptionSpec> specs = new ArrayList<>();
        public final List<UpdateListener> updateListeners = new ArrayList<>();
        public final List<StateListener> stateListeners = new ArrayList<>();
        public final List<SubscriptionHandle> active = new ArrayList<>();
        /** Every subscription ever made on this connection, in order, active or not. */
        public final List<Subscribed> subscriptions = new ArrayList<>();
        public boolean closed;

        FakeConnection(String serverAddress, String user, String password,
                ConnectionListener listener) {
            this.serverAddress = serverAddress;
            this.user = user;
            this.password = password;
            this.listener = listener;
        }

        @Override
        public SubscriptionHandle subscribe(SubscriptionSpec spec, UpdateListener updates,
                StateListener state) {
            specs.add(spec);
            updateListeners.add(updates);
            stateListeners.add(state);
            FakeHandle handle = new FakeHandle(spec);
            active.add(handle);
            subscriptions.add(new Subscribed(spec, updates, state, handle));
            return handle;
        }

        @Override
        public void unsubscribe(SubscriptionHandle handle) {
            if (refuseUnsubscribe) {
                throw new IllegalStateException("unsubscribe refused");
            }
            FakeHandle fake = (FakeHandle) handle;
            if (!fake.active) {
                throw new IllegalStateException("Subscription is not active"); // the SDK's contract
            }
            fake.active = false;
            active.remove(handle);
        }

        /** Closing fires the farewell {@code DISCONNECTED} the SDK sends — after the owner has moved
         * on, so the generation gate is exercised on every rebuild. */
        @Override
        public void close() {
            closed = true;
            listener.onStatusChange("DISCONNECTED");
        }

        // --- playing the wire back ------------------------------------------------------------

        /** The active subscriptions carrying {@code item} — fails loud if there are none, since a
         * scenario delivering to an item nobody subscribed is a scenario error, not a no-op. */
        public List<Subscribed> activeFor(String item) {
            List<Subscribed> matches = new ArrayList<>();
            for (Subscribed s : subscriptions) {
                if (s.handle().active() && s.carries(item)) {
                    matches.add(s);
                }
            }
            if (matches.isEmpty()) {
                throw new IllegalArgumentException("no active subscription carries " + item);
            }
            return matches;
        }

        /** Deliver one update to every active subscription carrying {@code item}. */
        public void deliver(String item, Map<String, @Nullable String> fields) {
            for (Subscribed s : activeFor(item)) {
                s.updates().onUpdate(item, fields);
            }
        }

        /** The server accepted the subscription(s) carrying {@code item}. */
        public void confirm(String item) {
            for (Subscribed s : activeFor(item)) {
                s.state().onSubscribed();
            }
        }

        /** The server rejected the subscription(s) carrying {@code item}. */
        public void reject(String item, int code, String message) {
            for (Subscribed s : activeFor(item)) {
                s.state().onSubscriptionError(code, message);
            }
        }

        /** Items with an active subscription, in subscription order. */
        public List<String> activeItems() {
            List<String> items = new ArrayList<>();
            for (Subscribed s : subscriptions) {
                if (s.handle().active()) {
                    items.addAll(s.spec().items());
                }
            }
            return items;
        }
    }

    @Override
    public Connection connect(String serverAddress, String user, String password,
            ConnectionListener listener) {
        if (failNextConnect || refuseConnects) {
            failNextConnect = false;
            throw new IllegalStateException("connect refused");
        }
        FakeConnection connection = new FakeConnection(serverAddress, user, password, listener);
        connections.add(connection);
        return connection;
    }

    /** The most recently opened connection. */
    public FakeConnection last() {
        return connections.getLast();
    }

    /** Lightstreamer field maps in the exact shape the IG items carry, so what a test delivers
     * goes through the real {@link StreamParsers}. */
    public static final class Wire {
        private Wire() {
        }

        public static Map<String, @Nullable String> tick(long timestampMs, String bid, String ask,
                String dealFlag) {
            Map<String, @Nullable String> fields = new LinkedHashMap<>();
            fields.put("TIMESTAMP", Long.toString(timestampMs));
            fields.put("BIDPRICE1", bid);
            fields.put("ASKPRICE1", ask);
            fields.put("DLG_FLAG", dealFlag);
            return fields;
        }

        /** A sealed 1-minute candle ({@code CONS_END=1}); {@code ltv} may be null as on the wire. */
        public static Map<String, @Nullable String> sealedBar(long utmMs, Ohlc bid, Ohlc offer,
                @Nullable Long ltv) {
            Map<String, @Nullable String> fields = new LinkedHashMap<>();
            fields.put("UTM", Long.toString(utmMs));
            fields.put("CONS_END", "1");
            put(fields, "BID_", bid);
            put(fields, "OFR_", offer);
            fields.put("LTV", ltv == null ? null : Long.toString(ltv));
            return fields;
        }

        private static void put(Map<String, @Nullable String> fields, String prefix, Ohlc ohlc) {
            fields.put(prefix + "OPEN", ohlc.open().toPlainString());
            fields.put(prefix + "HIGH", ohlc.high().toPlainString());
            fields.put(prefix + "LOW", ohlc.low().toPlainString());
            fields.put(prefix + "CLOSE", ohlc.close().toPlainString());
        }
    }
}
