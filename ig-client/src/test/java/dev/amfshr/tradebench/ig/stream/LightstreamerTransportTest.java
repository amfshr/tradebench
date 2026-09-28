package dev.amfshr.tradebench.ig.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.lightstreamer.client.Subscription;

class LightstreamerTransportTest {

    @Test
    void mapsSpecToTheSdkSubscriptionExactly() {
        Subscription subscription = LightstreamerTransport.toSubscription(
                new StreamTransport.SubscriptionSpec("MERGE", List.of("PRICE:Z6CS3E:X"),
                        List.of("TIMESTAMP", "BIDPRICE1"), "Pricing"));

        assertEquals("MERGE", subscription.getMode());
        assertEquals(List.of("PRICE:Z6CS3E:X"), Arrays.asList(subscription.getItems()));
        assertEquals(List.of("TIMESTAMP", "BIDPRICE1"), Arrays.asList(subscription.getFields()));
        assertEquals("Pricing", subscription.getDataAdapter());
    }

    @Test
    void extractsTheSubscribedFieldsFromAnItemUpdate() {
        StreamTransport.SubscriptionSpec spec = new StreamTransport.SubscriptionSpec("MERGE",
                List.of("PRICE:Z6CS3E:X"), List.of("TIMESTAMP", "BIDPRICE1"), "Pricing");
        var update = fakeItemUpdate("PRICE:Z6CS3E:X",
                java.util.Map.of("TIMESTAMP", "1790348223250", "BIDPRICE1", "24510.5"));

        var fields = LightstreamerTransport.extractFields(update, spec);

        assertEquals("1790348223250", fields.get("TIMESTAMP"));
        assertEquals("24510.5", fields.get("BIDPRICE1"));
        assertEquals(2, fields.size(), "only subscribed fields are extracted");
    }

    @Test
    void itemNameFallsBackToTheSpecWhenTheUpdateCarriesNone() {
        StreamTransport.SubscriptionSpec spec = new StreamTransport.SubscriptionSpec("MERGE",
                List.of("CHART:X:1MINUTE"), List.of("UTM"), null);

        assertEquals("CHART:X:1MINUTE", LightstreamerTransport.itemNameOf(
                fakeItemUpdate(null, java.util.Map.of()), spec));
        assertEquals("real-name", LightstreamerTransport.itemNameOf(
                fakeItemUpdate("real-name", java.util.Map.of()), spec));
    }

    private static com.lightstreamer.client.ItemUpdate fakeItemUpdate(String itemName,
            java.util.Map<String, String> values) {
        return (com.lightstreamer.client.ItemUpdate) java.lang.reflect.Proxy.newProxyInstance(
                LightstreamerTransportTest.class.getClassLoader(),
                new Class<?>[] {com.lightstreamer.client.ItemUpdate.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getItemName" -> itemName;
                    case "getValue" -> args[0] instanceof String field ? values.get(field) : null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void omittedAdapterStaysServerDefault() {
        Subscription subscription = LightstreamerTransport.toSubscription(
                new StreamTransport.SubscriptionSpec("MERGE", List.of("CHART:X:1MINUTE"),
                        List.of("UTM"), null));

        assertNull(subscription.getDataAdapter());
    }
}
