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
    void omittedAdapterStaysServerDefault() {
        Subscription subscription = LightstreamerTransport.toSubscription(
                new StreamTransport.SubscriptionSpec("MERGE", List.of("CHART:X:1MINUTE"),
                        List.of("UTM"), null));

        assertNull(subscription.getDataAdapter());
    }
}
