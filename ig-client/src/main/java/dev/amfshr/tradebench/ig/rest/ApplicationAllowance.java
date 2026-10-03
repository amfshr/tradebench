package dev.amfshr.tradebench.ig.rest;

/**
 * One entry of {@code GET /operations/application}: IG's real per-minute budgets for this
 * account and key. The published 30/min is not what every environment enforces (demo keys:
 * 10/min), so the collector discovers this after login rather than assuming (§6).
 */
public record ApplicationAllowance(String apiKey, int allowanceAccountOverall,
        int allowanceApplicationOverall, int allowanceAccountTrading,
        int allowanceAccountHistoricalData, int concurrentSubscriptionsLimit) {
}
