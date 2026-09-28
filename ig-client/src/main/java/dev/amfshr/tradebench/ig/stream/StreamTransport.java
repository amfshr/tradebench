package dev.amfshr.tradebench.ig.stream;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/** The Lightstreamer seam: everything above it tests on fakes (the simulator's seed). */
public interface StreamTransport {

    Connection connect(String serverAddress, String user, String password,
            ConnectionListener listener);

    interface Connection extends AutoCloseable {

        void subscribe(SubscriptionSpec spec, UpdateListener updates, StateListener state);

        @Override
        void close();
    }

    record SubscriptionSpec(String mode, List<String> items, List<String> fields,
            @Nullable String dataAdapter) {
    }

    /** Field values may be absent on any update — MERGE deltas carry changed fields only. */
    interface UpdateListener {

        void onUpdate(String itemName, Map<String, @Nullable String> fields);
    }

    interface ConnectionListener {

        void onStatusChange(String status);

        void onServerError(int code, String message);
    }

    interface StateListener {

        void onSubscribed();

        void onSubscriptionError(int code, String message);
    }
}
