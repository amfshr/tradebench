package dev.amfshr.tradebench.ig.stream;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Pure field-map parsing, run on the Lightstreamer thread: never throws, never blocks —
 * malformed input yields null and the caller counts it (§2.4 callback discipline).
 */
public final class StreamParsers {

    private StreamParsers() {
    }

    public static @Nullable TickUpdate parseTick(String epic,
            Map<String, @Nullable String> fields) {
        Instant timestamp = epochMs(fields.get("TIMESTAMP"));
        BigDecimal bid = decimal(fields.get("BIDPRICE1"));
        BigDecimal ask = decimal(fields.get("ASKPRICE1"));
        String dealFlag = fields.get("DLG_FLAG");
        if (timestamp == null || bid == null || ask == null || dealFlag == null) {
            return null;
        }
        // The wire pads DLG_FLAG with trailing spaces ("DEAL ") — §2.2.
        return new TickUpdate(epic, timestamp, bid, ask, dealFlag.strip());
    }

    /** True when this CHART update is a completed candle (CONS_END=1) — else it is expected noise. */
    public static boolean isSealed(Map<String, @Nullable String> fields) {
        return "1".equals(fields.get("CONS_END"));
    }

    public static @Nullable SealedBarUpdate parseSealedBar(String epic,
            Map<String, @Nullable String> fields) {
        Instant start = epochMs(fields.get("UTM"));
        Ohlc bid = ohlc(fields, "BID_");
        Ohlc offer = ohlc(fields, "OFR_");
        if (start == null || bid == null || offer == null) {
            return null;
        }
        return new SealedBarUpdate(epic, start, bid, offer, longOrNull(fields.get("LTV")));
    }

    private static @Nullable Ohlc ohlc(Map<String, @Nullable String> fields, String prefix) {
        BigDecimal open = decimal(fields.get(prefix + "OPEN"));
        BigDecimal high = decimal(fields.get(prefix + "HIGH"));
        BigDecimal low = decimal(fields.get(prefix + "LOW"));
        BigDecimal close = decimal(fields.get(prefix + "CLOSE"));
        if (open == null || high == null || low == null || close == null) {
            return null;
        }
        return new Ohlc(open, high, low, close);
    }

    private static @Nullable Instant epochMs(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(value.strip()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static @Nullable BigDecimal decimal(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static @Nullable Long longOrNull(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
