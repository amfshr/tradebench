package dev.amfshr.tradebench.ig.rest;

import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The /markets/{epic} snapshot E1 needs: identity, live market status, streamability, and
 * the dealing-rules snapshot (read live at startup, config only as fallback — §5.2). The
 * OMS-era model (currencies, lot sizes, margin bands) grows here when E6 needs it.
 */
public record MarketDetails(String epic, String instrumentName, String marketStatus,
        boolean streamingPricesAvailable, Map<String, DealingRule> dealingRules,
        @Nullable String marketOrderPreference, @Nullable String trailingStopsPreference) {
}
