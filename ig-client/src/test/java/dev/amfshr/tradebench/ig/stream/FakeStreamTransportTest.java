package dev.amfshr.tradebench.ig.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The fake's wire contract — what the scenario harness leans on (E1-T11 increment 1). */
class FakeStreamTransportTest {

    private static final String DAX_PRICE = "PRICE:Z6CS3E:IX.D.DAX.DAILY.IP";
    private static final String DAX_CHART = "CHART:IX.D.DAX.DAILY.IP:1MINUTE";

    private final FakeStreamTransport fake = new FakeStreamTransport();
    private final List<String> events = new ArrayList<>();
    private FakeStreamTransport.FakeConnection connection;
    private StreamTransport.SubscriptionHandle price;
    private StreamTransport.SubscriptionHandle chart;

    private StreamTransport.UpdateListener updates(String leg) {
        return (item, fields) -> events.add("update:" + leg + ":" + item);
    }

    private StreamTransport.StateListener state(String leg) {
        return new StreamTransport.StateListener() {
            @Override
            public void onSubscribed() {
                events.add("subscribed:" + leg);
            }

            @Override
            public void onSubscriptionError(int code, String message) {
                events.add("rejected:" + leg + ":" + code);
            }
        };
    }

    private static StreamTransport.SubscriptionSpec spec(String item) {
        return new StreamTransport.SubscriptionSpec("MERGE", List.of(item), List.of("F"), null);
    }

    @BeforeEach
    void connect() {
        connection = (FakeStreamTransport.FakeConnection) fake.connect("wss://x", "u", "p",
                new StreamTransport.ConnectionListener() {
                    @Override
                    public void onStatusChange(String status) {
                    }

                    @Override
                    public void onServerError(int code, String message) {
                    }
                });
        price = connection.subscribe(spec(DAX_PRICE), updates("price"), state("price"));
        chart = connection.subscribe(spec(DAX_CHART), updates("chart"), state("chart"));
    }

    @Test
    void unsubscribingAnInactiveHandleThrowsAsTheSdkDoes() {
        connection.unsubscribe(price);

        assertFalse(((FakeStreamTransport.FakeHandle) price).active());
        assertThrows(IllegalStateException.class, () -> connection.unsubscribe(price),
                "the SDK refuses a second unsubscribe — a fake that tolerates it hides a wedge");
        assertEquals(List.of(DAX_CHART), connection.activeItems(), "the twin is untouched");
    }

    @Test
    void aRefusedUnsubscribeLeavesTheHandleActive() {
        fake.refuseUnsubscribe = true;

        assertThrows(IllegalStateException.class, () -> connection.unsubscribe(price));

        assertTrue(((FakeStreamTransport.FakeHandle) price).active(), "refused, so still live on the wire");
        assertEquals(List.of(DAX_PRICE, DAX_CHART), connection.activeItems());
    }

    @Test
    void deliverRoutesByItemToThatSubscriptionOnly() {
        connection.deliver(DAX_CHART, Map.of("F", "1"));

        assertEquals(List.of("update:chart:" + DAX_CHART), events, "the price leg heard nothing");
    }

    @Test
    void deliveringToAnItemNobodySubscribedFailsLoud() {
        connection.unsubscribe(price);

        assertThrows(IllegalArgumentException.class, () -> connection.deliver(DAX_PRICE, Map.of("F", "1")),
                "a scenario delivering to an unsubscribed item is a scenario error, not a no-op");
    }

    @Test
    void confirmAndRejectReachTheRightStateListener() {
        connection.confirm(DAX_PRICE);
        connection.reject(DAX_CHART, 17, "no such item");

        assertEquals(List.of("subscribed:price", "rejected:chart:17"), events);
    }

    @Test
    void refusingConnectsIsStickyUntilCleared() {
        fake.refuseConnects = true;
        StreamTransport.ConnectionListener listener = connection.listener;

        assertThrows(IllegalStateException.class, () -> fake.connect("wss://x", "u", "p", listener));
        assertThrows(IllegalStateException.class, () -> fake.connect("wss://x", "u", "p", listener),
                "IG stays unreachable for as long as the scenario says — not one refusal");

        fake.refuseConnects = false;
        assertNotNull(fake.connect("wss://x", "u", "p", listener));
        assertEquals(2, fake.connections.size());
    }

    @Test
    void theWireBuildersParseThroughTheRealParsers() {
        Map<String, @Nullable String> tick = FakeStreamTransport.Wire.tick(1_727_427_601_250L,
                "24510.5", "24511.7", "DEAL ");
        Map<String, @Nullable String> bar = FakeStreamTransport.Wire.sealedBar(1_727_427_600_000L,
                new Ohlc(new BigDecimal("1"), new BigDecimal("2"), new BigDecimal("0.5"), new BigDecimal("1.5")),
                new Ohlc(new BigDecimal("1.1"), new BigDecimal("2.1"), new BigDecimal("0.6"), new BigDecimal("1.6")),
                42L);

        TickUpdate parsedTick = StreamParsers.parseTick("IX.D.DAX.DAILY.IP", tick);
        SealedBarUpdate parsedBar = StreamParsers.parseSealedBar("IX.D.DAX.DAILY.IP", bar);

        assertNotNull(parsedTick, "the builder speaks the parser's field names");
        assertEquals(Instant.ofEpochMilli(1_727_427_601_250L), parsedTick.timestampUtc());
        assertEquals(new BigDecimal("24511.7"), parsedTick.ask());
        assertEquals("DEAL", parsedTick.dealFlag(), "the wire's padded flag is stripped by the parser");
        assertNotNull(parsedBar);
        assertEquals(Instant.ofEpochMilli(1_727_427_600_000L), parsedBar.startUtc());
        assertEquals(new BigDecimal("1.6"), parsedBar.offer().close());
        assertEquals(42L, parsedBar.lastTradedVolume());
        assertEquals("1", bar.get("CONS_END"), "sealed — the chart leg's filter lets it through");
    }
}
