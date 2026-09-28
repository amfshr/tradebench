package dev.amfshr.tradebench.ig.stream;

import java.util.List;

import dev.amfshr.tradebench.ig.session.IgSession;

/**
 * Opens the authenticated Lightstreamer session (§1.1 wiring: user = account id, password =
 * "CST-…|XST-…") and subscribes markets as PRICE+CHART pairs — per-market, so one market's
 * failure never has to take down another (triage D16, T5's witness rule builds on this).
 */
public final class IgStreamClient {

    static final List<String> PRICE_FIELDS =
            List.of("TIMESTAMP", "BIDPRICE1", "ASKPRICE1", "DLG_FLAG");
    static final List<String> CHART_FIELDS = List.of("UTM", "CONS_END",
            "BID_OPEN", "BID_HIGH", "BID_LOW", "BID_CLOSE",
            "OFR_OPEN", "OFR_HIGH", "OFR_LOW", "OFR_CLOSE", "LTV");
    static final String MERGE = "MERGE";
    static final String PRICE_ADAPTER = "Pricing";

    private final LsTransport transport;

    public IgStreamClient(LsTransport transport) {
        this.transport = transport;
    }

    public IgStreamSession connect(IgSession session, StreamEvents events,
            LsTransport.ConnectionListener connectionListener) {
        LsTransport.Connection connection = transport.connect(
                session.lightstreamerEndpoint(),
                session.activeAccountId(),
                session.tokens().lightstreamerPassword(),
                connectionListener);
        return new IgStreamSession(connection, session.activeAccountId(), events);
    }

    public static final class IgStreamSession implements AutoCloseable {

        private final LsTransport.Connection connection;
        private final String accountId;
        private final StreamEvents events;

        private IgStreamSession(LsTransport.Connection connection, String accountId,
                StreamEvents events) {
            this.connection = connection;
            this.accountId = accountId;
            this.events = events;
        }

        /** Subscribes one market's PRICE+CHART pair; per-market state goes to the listener. */
        public void subscribeMarket(String epic, LsTransport.StateListener priceState,
                LsTransport.StateListener chartState) {
            String priceItem = "PRICE:" + accountId + ":" + epic;
            connection.subscribe(
                    new LsTransport.SubscriptionSpec(MERGE, List.of(priceItem), PRICE_FIELDS,
                            PRICE_ADAPTER),
                    (item, fields) -> {
                        TickUpdate tick = StreamParsers.parseTick(epic, fields);
                        if (tick == null) {
                            events.onMalformed(item);
                        } else {
                            events.onTick(tick);
                        }
                    },
                    priceState);
            String chartItem = "CHART:" + epic + ":1MINUTE";
            // No data adapter for CHART — demo/live expose only the default (§2.1).
            connection.subscribe(
                    new LsTransport.SubscriptionSpec(MERGE, List.of(chartItem), CHART_FIELDS,
                            null),
                    (item, fields) -> {
                        if (!StreamParsers.isSealed(fields)) {
                            return;
                        }
                        SealedBarUpdate bar = StreamParsers.parseSealedBar(epic, fields);
                        if (bar == null) {
                            events.onMalformed(item);
                        } else {
                            events.onSealedBar(bar);
                        }
                    },
                    chartState);
        }

        @Override
        public void close() {
            connection.close();
        }
    }
}
