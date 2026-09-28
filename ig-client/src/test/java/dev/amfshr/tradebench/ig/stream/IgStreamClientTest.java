package dev.amfshr.tradebench.ig.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;

import dev.amfshr.tradebench.ig.session.IgSession;
import dev.amfshr.tradebench.ig.session.IgTokens;

class IgStreamClientTest {

    private static final String DAX = "IX.D.DAX.DAILY.IP";

    private FakeLs fake;
    private RecordingEvents events;
    private IgStreamClient.IgStreamSession streamSession;

    private static final class FakeLs implements LsTransport {
        String serverAddress;
        String user;
        String password;
        final List<SubscriptionSpec> specs = new ArrayList<>();
        final List<UpdateListener> updateListeners = new ArrayList<>();
        boolean closed;

        @Override
        public Connection connect(String serverAddress, String user, String password,
                ConnectionListener listener) {
            this.serverAddress = serverAddress;
            this.user = user;
            this.password = password;
            return new Connection() {
                @Override
                public void subscribe(SubscriptionSpec spec, UpdateListener updates,
                        StateListener state) {
                    specs.add(spec);
                    updateListeners.add(updates);
                }

                @Override
                public void close() {
                    closed = true;
                }
            };
        }
    }

    private static final class RecordingEvents implements StreamEvents {
        final List<TickUpdate> ticks = new ArrayList<>();
        final List<SealedBarUpdate> bars = new ArrayList<>();
        final List<String> malformed = new ArrayList<>();

        @Override
        public void onTick(TickUpdate tick) {
            ticks.add(tick);
        }

        @Override
        public void onSealedBar(SealedBarUpdate bar) {
            bars.add(bar);
        }

        @Override
        public void onMalformed(String itemName) {
            malformed.add(itemName);
        }
    }

    @BeforeEach
    void setUp() {
        fake = new FakeLs();
        events = new RecordingEvents();
        IgSession session = new IgSession(new IgTokens("cstA", "xstA"), "Z6CS3E",
                "https://demo-apd.marketdatasystems.com", List.of());
        streamSession = new IgStreamClient(fake).connect(session, events, new NoopConnection());
        streamSession.subscribeMarket(DAX, new NoopState(), new NoopState());
    }

    @Test
    void connectsWithTheSessionsEndpointAccountAndTokenPassword() {
        assertEquals("https://demo-apd.marketdatasystems.com", fake.serverAddress);
        assertEquals("Z6CS3E", fake.user);
        assertEquals("CST-cstA|XST-xstA", fake.password);
    }

    @Test
    void subscribesThePriceChartPairExactly() {
        assertEquals(2, fake.specs.size());
        LsTransport.SubscriptionSpec price = fake.specs.get(0);
        assertEquals("MERGE", price.mode());
        assertEquals(List.of("PRICE:Z6CS3E:" + DAX), price.items());
        assertEquals(List.of("TIMESTAMP", "BIDPRICE1", "ASKPRICE1", "DLG_FLAG"), price.fields());
        assertEquals("Pricing", price.dataAdapter());

        LsTransport.SubscriptionSpec chart = fake.specs.get(1);
        assertEquals("MERGE", chart.mode());
        assertEquals(List.of("CHART:" + DAX + ":1MINUTE"), chart.items());
        assertEquals(List.of("UTM", "CONS_END",
                "BID_OPEN", "BID_HIGH", "BID_LOW", "BID_CLOSE",
                "OFR_OPEN", "OFR_HIGH", "OFR_LOW", "OFR_CLOSE", "LTV"), chart.fields());
        assertNull(chart.dataAdapter());
    }

    @Test
    void priceUpdatesFlowThroughParsingToTheSink() {
        Map<String, @Nullable String> fields = new HashMap<>();
        fields.put("TIMESTAMP", "1790348223250");
        fields.put("BIDPRICE1", "24510.5");
        fields.put("ASKPRICE1", "24511.7");
        fields.put("DLG_FLAG", "DEAL ");

        fake.updateListeners.get(0).onUpdate("PRICE:Z6CS3E:" + DAX, fields);

        assertEquals(List.of(new TickUpdate(DAX, Instant.parse("2026-09-25T14:57:03.250Z"),
                new BigDecimal("24510.5"), new BigDecimal("24511.7"), "DEAL")), events.ticks);
        assertTrue(events.malformed.isEmpty());
    }

    @Test
    void garbagePriceUpdateCountsAsMalformed() {
        fake.updateListeners.get(0).onUpdate("PRICE:Z6CS3E:" + DAX,
                Map.of("TIMESTAMP", "garbage"));

        assertTrue(events.ticks.isEmpty());
        assertEquals(List.of("PRICE:Z6CS3E:" + DAX), events.malformed);
    }

    @Test
    void inProgressCandleIsSilentlyIgnoredNeverMalformed() {
        Map<String, @Nullable String> fields = new HashMap<>();
        fields.put("CONS_END", "0");
        fields.put("UTM", "1790348220000");

        fake.updateListeners.get(1).onUpdate("CHART:" + DAX + ":1MINUTE", fields);

        assertTrue(events.bars.isEmpty());
        assertTrue(events.malformed.isEmpty());
    }

    @Test
    void sealedCandleMissingACornerIsMalformed() {
        Map<String, @Nullable String> fields = new HashMap<>();
        fields.put("CONS_END", "1");
        fields.put("UTM", "1790348220000");

        fake.updateListeners.get(1).onUpdate("CHART:" + DAX + ":1MINUTE", fields);

        assertTrue(events.bars.isEmpty());
        assertEquals(List.of("CHART:" + DAX + ":1MINUTE"), events.malformed);
    }

    @Test
    void closeClosesTheConnection() {
        streamSession.close();
        assertTrue(fake.closed);
    }

    private static final class NoopConnection implements LsTransport.ConnectionListener {
        @Override
        public void onStatusChange(String status) {
        }

        @Override
        public void onServerError(int code, String message) {
        }
    }

    private static final class NoopState implements LsTransport.StateListener {
        @Override
        public void onSubscribed() {
        }

        @Override
        public void onSubscriptionError(int code, String message) {
        }
    }
}
