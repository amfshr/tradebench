package dev.amfshr.tradebench.ig.rest;

/**
 * The historical-price allowance metadata IG returns with every /prices response. The weekly
 * budget (10,000 points, §6) is real and shared across services on the account — T6's heal
 * job plans against {@code remainingAllowance} rather than assuming.
 */
public record Allowance(long remainingAllowance, long totalAllowance, long allowanceExpirySeconds) {
}
