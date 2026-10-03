package dev.amfshr.tradebench.ig.stream;

import java.util.ArrayList;
import java.util.List;

/**
 * The Lightstreamer seam, faked: records every connection and subscription so tests above the
 * transport (the stream client, the collection service's supervisor shell) assert on exactly
 * what reached the wire. Captures each subscription's {@link StateListener} so a test can
 * confirm or reject it, and injects the two failures the resilience belt must survive —
 * a refused connect and a refused unsubscribe. Shared via {@code java-test-fixtures}.
 */
public final class FakeStreamTransport implements StreamTransport {

    /** Every connection ever opened, oldest first — a rebuild opens a new one. */
    public final List<FakeConnection> connections = new ArrayList<>();
    /** When set, the next {@link #connect} throws (and clears the flag). */
    public boolean failNextConnect;
    /** When set, every {@link Connection#unsubscribe} throws — the LS refusal case (§3.5). */
    public boolean refuseUnsubscribe;

    /** Identity, not value: two subscriptions to the same spec are two distinct handles, as on
     * the real transport — so dropping one never drops its twin. */
    public static final class FakeHandle implements SubscriptionHandle {
        private final SubscriptionSpec spec;

        FakeHandle(SubscriptionSpec spec) {
            this.spec = spec;
        }

        public SubscriptionSpec spec() {
            return spec;
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
            SubscriptionHandle handle = new FakeHandle(spec);
            active.add(handle);
            return handle;
        }

        @Override
        public void unsubscribe(SubscriptionHandle handle) {
            if (refuseUnsubscribe) {
                throw new IllegalStateException("unsubscribe refused");
            }
            active.remove(handle);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Override
    public Connection connect(String serverAddress, String user, String password,
            ConnectionListener listener) {
        if (failNextConnect) {
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
}
