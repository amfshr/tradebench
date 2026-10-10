package dev.amfshr.tradebench.ig.stream;

import java.util.List;

/**
 * One authenticated stream connection. Exposes IG's subscriptions as separate capabilities
 * — which to combine (e.g. E1's PRICE+CHART pair per market) is the consumer's policy, not
 * this client's. Obtained from {@link IgStreamClient#connect}.
 */
public final class IgStreamSession implements AutoCloseable {

    static final List<String> PRICE_FIELDS =
            List.of("TIMESTAMP", "BIDPRICE1", "ASKPRICE1", "DLG_FLAG");
    static final List<String> CHART_FIELDS = List.of("UTM", "CONS_END",
            "BID_OPEN", "BID_HIGH", "BID_LOW", "BID_CLOSE",
            "OFR_OPEN", "OFR_HIGH", "OFR_LOW", "OFR_CLOSE", "LTV");
    static final String MERGE = "MERGE";
    static final String PRICE_ADAPTER = "Pricing";

    private final StreamTransport.Connection connection;
    private final String accountId;
    private final StreamEvents events;

    IgStreamSession(StreamTransport.Connection connection, String accountId, StreamEvents events) {
        this.connection = connection;
        this.accountId = accountId;
        this.events = events;
    }

    /** Bid/ask ticks — account-scoped item (the §1.1 wrong-account trap lives here). */
    public StreamTransport.SubscriptionHandle subscribePrice(String epic,
            StreamTransport.StateListener state) {
        String priceItem = "PRICE:" + accountId + ":" + epic;
        return connection.subscribe(
                new StreamTransport.SubscriptionSpec(MERGE, List.of(priceItem), PRICE_FIELDS,
                        PRICE_ADAPTER),
                (item, fields) -> {
                    TickUpdate tick = StreamParsers.parseTick(epic, fields);
                    if (tick == null) {
                        events.onMalformed(epic, item);
                    } else {
                        events.onTick(tick);
                    }
                },
                state);
    }

    /** Sealed 1-minute candles (CONS_END=1); in-progress updates are filtered here. */
    public StreamTransport.SubscriptionHandle subscribeChart1m(String epic,
            StreamTransport.StateListener state) {
        String chartItem = "CHART:" + epic + ":1MINUTE";
        // No data adapter for CHART — demo/live expose only the default (§2.1).
        return connection.subscribe(
                new StreamTransport.SubscriptionSpec(MERGE, List.of(chartItem), CHART_FIELDS, null),
                (item, fields) -> {
                    String consEnd = fields.get("CONS_END");
                    if (consEnd == null) {
                        events.onMalformed(epic, item);
                        return;
                    }
                    if (!"1".equals(consEnd)) {
                        return;
                    }
                    SealedBarUpdate bar = StreamParsers.parseSealedBar(epic, fields);
                    if (bar == null) {
                        events.onMalformed(epic, item);
                    } else {
                        events.onSealedBar(bar);
                    }
                },
                state);
    }

    /** Drop one market's subscription (its price or chart handle) without disturbing the others —
     * surgical per-market recovery (§3.5); pairing is the service's policy, so is which half to drop. */
    public void unsubscribe(StreamTransport.SubscriptionHandle handle) {
        connection.unsubscribe(handle);
    }

    @Override
    public void close() {
        connection.close();
    }
}
