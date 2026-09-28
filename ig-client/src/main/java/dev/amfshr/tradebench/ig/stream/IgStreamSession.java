package dev.amfshr.tradebench.ig.stream;

import java.util.List;

/**
 * One authenticated stream connection; markets subscribe as per-market PRICE+CHART pairs
 * (D16) so one market's failure never has to take down another (T5's witness rule builds
 * on this). Obtained from {@link IgStreamClient#connect}.
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

    public void subscribeMarket(String epic, StreamTransport.StateListener priceState,
            StreamTransport.StateListener chartState) {
        String priceItem = "PRICE:" + accountId + ":" + epic;
        connection.subscribe(
                new StreamTransport.SubscriptionSpec(MERGE, List.of(priceItem), PRICE_FIELDS,
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
                new StreamTransport.SubscriptionSpec(MERGE, List.of(chartItem), CHART_FIELDS, null),
                (item, fields) -> {
                    String consEnd = fields.get("CONS_END");
                    if (consEnd == null) {
                        events.onMalformed(item);
                        return;
                    }
                    if (!"1".equals(consEnd)) {
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
