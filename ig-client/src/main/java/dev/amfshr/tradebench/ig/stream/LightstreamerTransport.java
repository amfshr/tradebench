package dev.amfshr.tradebench.ig.stream;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.lightstreamer.client.ClientListener;
import com.lightstreamer.client.ItemUpdate;
import com.lightstreamer.client.LightstreamerClient;
import com.lightstreamer.client.Subscription;
import com.lightstreamer.client.SubscriptionListener;

/** The real transport over the official LS SDK — the only class touching a socket here. */
public final class LightstreamerTransport implements StreamTransport {

    @Override
    public Connection connect(String serverAddress, String user, String password,
            ConnectionListener listener) {
        LightstreamerClient client = new LightstreamerClient(serverAddress, null);
        client.connectionDetails.setUser(user);
        client.connectionDetails.setPassword(password);
        client.addListener(new ClientListener() {
            @Override
            public void onStatusChange(String status) {
                listener.onStatusChange(status);
            }

            @Override
            public void onServerError(int errorCode, String errorMessage) {
                listener.onServerError(errorCode, errorMessage);
            }

            @Override
            public void onListenEnd() {
            }

            @Override
            public void onListenStart() {
            }

            @Override
            public void onPropertyChange(String property) {
            }
        });
        client.connect();
        return new LsConnection(client);
    }

    static Map<String, @Nullable String> extractFields(ItemUpdate update,
            SubscriptionSpec spec) {
        Map<String, @Nullable String> fields = new HashMap<>();
        for (String field : spec.fields()) {
            fields.put(field, update.getValue(field));
        }
        return fields;
    }

    static String itemNameOf(ItemUpdate update, SubscriptionSpec spec) {
        String name = update.getItemName();
        return name != null ? name : spec.items().get(0);
    }

    static Subscription toSubscription(SubscriptionSpec spec) {
        Subscription subscription = new Subscription(spec.mode(),
                spec.items().toArray(String[]::new), spec.fields().toArray(String[]::new));
        if (spec.dataAdapter() != null) {
            subscription.setDataAdapter(spec.dataAdapter());
        }
        return subscription;
    }

    private static final class LsConnection implements Connection {

        private final LightstreamerClient client;

        private LsConnection(LightstreamerClient client) {
            this.client = client;
        }

        @Override
        public void subscribe(SubscriptionSpec spec, UpdateListener updates,
                StateListener state) {
            Subscription subscription = toSubscription(spec);
            subscription.addListener(new SubscriptionListener() {
                @Override
                public void onItemUpdate(ItemUpdate update) {
                    updates.onUpdate(itemNameOf(update, spec), extractFields(update, spec));
                }

                @Override
                public void onSubscription() {
                    state.onSubscribed();
                }

                @Override
                public void onSubscriptionError(int code, String message) {
                    state.onSubscriptionError(code, message);
                }

                @Override
                public void onUnsubscription() {
                }

                @Override
                public void onClearSnapshot(String itemName, int itemPos) {
                }

                @Override
                public void onCommandSecondLevelItemLostUpdates(int lostUpdates, String key) {
                }

                @Override
                public void onCommandSecondLevelSubscriptionError(int code, String message,
                        String key) {
                }

                @Override
                public void onEndOfSnapshot(String itemName, int itemPos) {
                }

                @Override
                public void onItemLostUpdates(String itemName, int itemPos, int lostUpdates) {
                }

                @Override
                public void onListenEnd() {
                }

                @Override
                public void onListenStart() {
                }

                @Override
                public void onRealMaxFrequency(String frequency) {
                }
            });
            client.subscribe(subscription);
        }

        @Override
        public void close() {
            client.disconnect();
        }
    }
}
