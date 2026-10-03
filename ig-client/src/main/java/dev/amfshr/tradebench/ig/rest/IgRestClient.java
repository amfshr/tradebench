package dev.amfshr.tradebench.ig.rest;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

import dev.amfshr.tradebench.ig.IgCredentials;
import dev.amfshr.tradebench.ig.IgEnvironment;
import dev.amfshr.tradebench.ig.error.IgErrors;
import dev.amfshr.tradebench.ig.http.HttpCall;
import dev.amfshr.tradebench.ig.http.HttpResult;
import dev.amfshr.tradebench.ig.http.HttpTransport;
import dev.amfshr.tradebench.ig.session.IgSession;

/**
 * The REST endpoints E1 needs: market details (dealing-rules snapshot) and historical prices
 * (T6's heal path) on v3, and the account's real request budget on v1. Methods take the {@link IgSession} explicitly — retry/rebuild
 * policy belongs to the caller's supervisor, never to the wire layer.
 */
public final class IgRestClient {

    /** Candles are keyed on {@code snapshotTimeUTC} — the only timezone-proof field (§4.2). */
    private static final DateTimeFormatter UTC_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final HttpTransport transport;
    private final IgEnvironment environment;
    private final IgCredentials credentials;
    private final RequestPacer pacer;
    // Exact decimals off the wire (triage D36): floats parse as BigDecimal, not double, and
    // the wire's scale is kept (default Jackson strips "8.0" to "8").
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .configure(com.fasterxml.jackson.databind.cfg.JsonNodeFeature
                    .STRIP_TRAILING_BIGDECIMAL_ZEROES, false);

    public IgRestClient(HttpTransport transport, IgEnvironment environment,
            IgCredentials credentials, RequestPacer pacer) {
        this.transport = transport;
        this.environment = environment;
        this.credentials = credentials;
        this.pacer = pacer;
    }

    public MarketDetails marketDetails(IgSession session, String epic)
            throws IOException, InterruptedException {
        HttpResult result = get(session, "/markets/" + epic, 3);
        if (result.status() != 200) {
            throw IgErrors.from(mapper, result, "GET /markets/" + epic);
        }
        JsonNode root = mapper.readTree(result.body());
        JsonNode instrument = root.path("instrument");
        JsonNode rules = root.path("dealingRules");
        Map<String, DealingRule> dealingRules = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : rules.properties()) {
            JsonNode rule = entry.getValue();
            if (rule.isObject() && rule.has("unit") && rule.has("value")) {
                dealingRules.put(entry.getKey(),
                        new DealingRule(rule.get("unit").asText(), rule.get("value").decimalValue()));
            }
        }
        return new MarketDetails(
                instrument.path("epic").asText(),
                instrument.path("name").asText(),
                root.path("snapshot").path("marketStatus").asText(),
                instrument.path("streamingPricesAvailable").asBoolean(),
                Map.copyOf(dealingRules),
                rules.path("marketOrderPreference").asText(null),
                rules.path("trailingStopsPreference").asText(null));
    }

    /**
     * The last {@code maxCandles} candles at {@code resolution} — deliberately date-free:
     * IG's from/to timezone semantics are unverified, so the service path requests "last N"
     * and filters by {@code snapshotTimeUTC} (§4.2). Callers wanting N sealed candles fetch
     * N+1 (the newest may be the forming one) and window-filter.
     */
    public PriceHistory recentPrices(IgSession session, String epic, Resolution resolution,
            int maxCandles) throws IOException, InterruptedException {
        String path = "/prices/" + epic + "?resolution=" + resolution
                + "&max=" + maxCandles + "&pageSize=" + maxCandles;
        HttpResult result = get(session, path, 3);
        if (result.status() != 200) {
            throw IgErrors.from(mapper, result, "GET /prices/" + epic);
        }
        JsonNode root = mapper.readTree(result.body());
        List<PriceCandle> candles = new ArrayList<>();
        for (JsonNode node : root.path("prices")) {
            candles.add(new PriceCandle(
                    snapshotInstant(node),
                    pricePoint(node.path("openPrice")),
                    pricePoint(node.path("highPrice")),
                    pricePoint(node.path("lowPrice")),
                    pricePoint(node.path("closePrice")),
                    node.hasNonNull("lastTradedVolume") ? node.get("lastTradedVolume").asLong()
                            : null));
        }
        int totalPages = root.path("metadata").path("pageData").path("totalPages").asInt(1);
        if (totalPages > 1) {
            throw new IllegalStateException("prices response is paged (totalPages=" + totalPages
                    + ") despite pageSize=max — refusing a silently truncated window");
        }
        JsonNode allowance = root.path("metadata").path("allowance");
        return new PriceHistory(List.copyOf(candles), new Allowance(
                allowance.path("remainingAllowance").asLong(),
                allowance.path("totalAllowance").asLong(),
                allowance.path("allowanceExpiry").asLong()));
    }

    private static Instant snapshotInstant(JsonNode candle) {
        String utc = candle.path("snapshotTimeUTC").asText(null);
        if (utc != null) {
            return LocalDateTime.parse(utc, UTC_FORMAT).toInstant(ZoneOffset.UTC);
        }
        // No guessing: snapshotTime is account-timezone and parsing it as UTC would mislabel
        // every bar while reporting success — the exact trap §4.2 exists to prevent. Fail
        // loud (P9); a heal classifies this as a raised failure, never silent corruption.
        throw new IllegalStateException(
                "price candle carries no snapshotTimeUTC — refusing to key a bar on the"
                        + " account-timezone snapshotTime (broker playbook §4.2)");
    }

    private static PricePoint pricePoint(JsonNode node) {
        return new PricePoint(
                requireDecimal(node, "bid"),
                requireDecimal(node, "ask"),
                decimalOrNull(node, "lastTraded"));
    }

    /** bid/ask are the candle's substance — a point without them is refused, never guessed. */
    private static BigDecimal requireDecimal(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            throw new IllegalStateException("price point missing required field '" + field
                    + "' — refusing a partial candle (the heal classifies this as failed)");
        }
        return node.get(field).decimalValue();
    }

    /** The real allowances for OUR key from {@code GET /operations/application} (v1), or null if
     * IG does not list it — another key's budget is never a stand-in. */
    public @Nullable ApplicationAllowance applicationAllowance(IgSession session)
            throws IOException, InterruptedException {
        HttpResult result = get(session, "/operations/application", 1);
        if (result.status() != 200) {
            throw IgErrors.from(mapper, result, "GET /operations/application");
        }
        for (JsonNode node : mapper.readTree(result.body())) {
            if (credentials.apiKey().equals(node.path("apiKey").asText())) {
                return new ApplicationAllowance(node.path("apiKey").asText(),
                        positive(node, "allowanceAccountOverall"),
                        positive(node, "allowanceApplicationOverall"),
                        node.path("allowanceAccountTrading").asInt(),
                        node.path("allowanceAccountHistoricalData").asInt(),
                        node.path("concurrentSubscriptionsLimit").asInt());
            }
        }
        return null;
    }

    // Both overall figures feed the pacer: a missing one must never read as 0 (then 1/min for life).
    private static int positive(JsonNode node, String field) {
        if (!node.hasNonNull(field) || node.get(field).asInt() < 1) {
            throw new IllegalStateException("GET /operations/application: our entry carries no usable "
                    + field + " — refusing to invent a budget");
        }
        return node.get(field).asInt();
    }

    private static @Nullable BigDecimal decimalOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).decimalValue() : null;
    }

    private HttpResult get(IgSession session, String pathAndQuery, int version)
            throws IOException, InterruptedException {
        pacer.acquire();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-IG-API-KEY", credentials.apiKey());
        headers.put("VERSION", String.valueOf(version));
        headers.put("Accept", "application/json; charset=UTF-8");
        headers.put("CST", session.tokens().cst());
        headers.put("X-SECURITY-TOKEN", session.tokens().securityToken());
        return transport.exchange(new HttpCall("GET",
                URI.create(environment.baseUrl() + pathAndQuery), headers, null));
    }
}
