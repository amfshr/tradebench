package dev.amfshr.tradebench.ig.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.ig.IgCredentials;
import dev.amfshr.tradebench.ig.IgEnvironment;
import dev.amfshr.tradebench.ig.error.IgRetryableException;
import dev.amfshr.tradebench.ig.http.HttpCall;
import dev.amfshr.tradebench.ig.session.IgSession;
import dev.amfshr.tradebench.ig.session.IgTokens;
import dev.amfshr.tradebench.ig.testutil.FakeTime;
import dev.amfshr.tradebench.ig.testutil.FakeTransport;
import dev.amfshr.tradebench.ig.testutil.Wire;

/** Golden-fixture contract tests: pinned wire bytes in, exact typed values out. */
class IgRestClientTest {

    private static final String DAX_EPIC = "IX.D.DAX.DAILY.IP";

    private FakeTransport transport;
    private IgRestClient client;
    private IgSession session;

    @BeforeEach
    void setUp() {
        transport = new FakeTransport();
        FakeTime time = new FakeTime();
        client = new IgRestClient(transport, IgEnvironment.DEMO,
                new IgCredentials("amfshr-demo", "pw", "placeholder-key", "Z6CS3E"),
                new RequestPacer(30, time.clock(), time.sleeper()));
        session = new IgSession(new IgTokens("cstA", "xstA"), "Z6CS3E",
                "https://demo-apd.marketdatasystems.com", List.of());
    }

    @Test
    void marketDetailsParsesIdentityStatusAndDealingRulesExactly() throws Exception {
        transport.enqueue(FakeTransport.json(200, Wire.fixture("market-details-dax.json")));

        MarketDetails details = client.marketDetails(session, DAX_EPIC);

        assertEquals(DAX_EPIC, details.epic());
        assertEquals("Germany 40", details.instrumentName());
        assertEquals("TRADEABLE", details.marketStatus());
        assertTrue(details.streamingPricesAvailable());
        // The load-bearing, drifting rule (§5.2) — with its unit, because units change env↔env.
        assertEquals(new DealingRule("POINTS", new BigDecimal("8.0")),
                details.dealingRules().get("minNormalStopOrLimitDistance"));
        assertEquals(new DealingRule("PERCENTAGE", new BigDecimal("2.0")),
                details.dealingRules().get("minControlledRiskStopDistance"));
        assertEquals(new DealingRule("POINTS", new BigDecimal("0.04")),
                details.dealingRules().get("minDealSize"));
        assertEquals(6, details.dealingRules().size(),
                "the six {unit,value} rules; string preferences are separate fields");
        assertEquals("AVAILABLE_DEFAULT_OFF", details.marketOrderPreference());
        assertEquals("AVAILABLE", details.trailingStopsPreference());
    }

    @Test
    void marketDetailsRequestCarriesV3AndSessionTokens() throws Exception {
        transport.enqueue(FakeTransport.json(200, Wire.fixture("market-details-dax.json")));

        client.marketDetails(session, DAX_EPIC);

        HttpCall call = transport.calls.get(0);
        assertEquals("GET", call.method());
        assertEquals("https://demo-api.ig.com/gateway/deal/markets/" + DAX_EPIC,
                call.uri().toString());
        assertEquals("3", call.headers().get("VERSION"));
        assertEquals("cstA", call.headers().get("CST"));
        assertEquals("xstA", call.headers().get("X-SECURITY-TOKEN"));
        assertEquals("placeholder-key", call.headers().get("X-IG-API-KEY"));
    }

    @Test
    void recentPricesParsesCandlesKeyedOnSnapshotTimeUtc() throws Exception {
        transport.enqueue(FakeTransport.json(200, Wire.fixture("prices-minute.json")));

        PriceHistory history = client.recentPrices(session, DAX_EPIC, Resolution.MINUTE, 3);

        assertEquals(3, history.candles().size());
        PriceCandle first = history.candles().get(0);
        // 15:57 London in the fixture's snapshotTime; the UTC field is authoritative (§4.2).
        assertEquals(Instant.parse("2026-09-25T14:57:00Z"), first.snapshotTimeUtc());
        assertEquals(new BigDecimal("24510.5"), first.open().bid());
        assertEquals(new BigDecimal("24511.7"), first.open().ask());
        assertNull(first.open().lastTraded());
        assertEquals(new BigDecimal("24514.8"), first.high().bid());
        assertEquals(new BigDecimal("24508.3"), first.low().bid());
        assertEquals(new BigDecimal("24512.0"), first.close().bid());
        assertEquals(321L, first.lastTradedVolume());
        assertEquals(Instant.parse("2026-09-25T14:59:00Z"),
                history.candles().get(2).snapshotTimeUtc());
    }

    @Test
    void recentPricesParsesTheAllowanceBudget() throws Exception {
        transport.enqueue(FakeTransport.json(200, Wire.fixture("prices-minute.json")));

        PriceHistory history = client.recentPrices(session, DAX_EPIC, Resolution.MINUTE, 3);

        assertEquals(new Allowance(9970, 10000, 518400), history.allowance());
    }

    @Test
    void recentPricesRequestsLastNWithoutDates() throws Exception {
        transport.enqueue(FakeTransport.json(200, Wire.fixture("prices-minute.json")));

        client.recentPrices(session, DAX_EPIC, Resolution.MINUTE, 3);

        // Date-free by design: from/to timezone semantics are unverified (§4.2).
        assertEquals("https://demo-api.ig.com/gateway/deal/prices/" + DAX_EPIC
                        + "?resolution=MINUTE&max=3&pageSize=3",
                transport.calls.get(0).uri().toString());
    }

    @Test
    void candleWithoutUtcFieldFailsLoudInsteadOfGuessing() {
        // snapshotTime is account-timezone; keying a bar on it as UTC silently mislabels the
        // data spine — §4.2's trap. The client must refuse, never guess (P9).
        String body = """
                {"prices":[{"snapshotTime":"2026/09/25 15:57:00",
                  "openPrice":{"bid":1.0,"ask":2.0},"closePrice":{"bid":1.0,"ask":2.0},
                  "highPrice":{"bid":1.0,"ask":2.0},"lowPrice":{"bid":1.0,"ask":2.0},
                  "lastTradedVolume":1}],
                 "metadata":{"allowance":{"remainingAllowance":1,"totalAllowance":2,
                  "allowanceExpiry":3}}}""";
        transport.enqueue(FakeTransport.json(200, body));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> client.recentPrices(session, DAX_EPIC, Resolution.MINUTE, 1));

        assertTrue(thrown.getMessage().contains("snapshotTimeUTC"),
                "the refusal must name the missing field, got: " + thrown.getMessage());
    }

    @Test
    void pricePointMissingBidOrAskFailsLoud() {
        // bid/ask are the candle's substance; a point without them is refused, never
        // nulled through (consistent with the no-guessing ruling — heal classifies failed).
        String body = """
                {"prices":[{"snapshotTimeUTC":"2026-09-25T14:57:00",
                  "openPrice":{"bid":1.0},"closePrice":{"bid":1.0,"ask":2.0},
                  "highPrice":{"bid":1.0,"ask":2.0},"lowPrice":{"bid":1.0,"ask":2.0},
                  "lastTradedVolume":1}],
                 "metadata":{"allowance":{"remainingAllowance":1,"totalAllowance":2,
                  "allowanceExpiry":3}}}""";
        transport.enqueue(FakeTransport.json(200, body));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> client.recentPrices(session, DAX_EPIC, Resolution.MINUTE, 1));

        assertTrue(thrown.getMessage().contains("'ask'"),
                "the refusal must name the missing field, got: " + thrown.getMessage());
    }

    @Test
    void allowanceExhaustionIsRetryableWithTheCodePreserved() {
        transport.enqueue(FakeTransport.json(403,
                "{\"errorCode\":\"error.public-api.exceeded-account-historical-data-allowance\"}"));

        IgRetryableException thrown = assertThrows(IgRetryableException.class,
                () -> client.recentPrices(session, DAX_EPIC, Resolution.MINUTE, 3));

        assertEquals("error.public-api.exceeded-account-historical-data-allowance",
                thrown.errorCode());
        assertEquals(403, thrown.httpStatus());
    }
}
