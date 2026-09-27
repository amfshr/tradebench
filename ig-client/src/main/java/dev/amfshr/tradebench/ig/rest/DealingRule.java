package dev.amfshr.tradebench.ig.rest;

import java.math.BigDecimal;

/**
 * One dealing rule as a {unit, value} pair. Units matter: two of the eight rules arrive as
 * PERCENTAGE and one changes unit between demo and live — consumers must check
 * {@code unit.equals("POINTS")} before using a value as a distance (§5.1). Never hardcode
 * these values; they drift, sometimes daily (§5.2).
 */
public record DealingRule(String unit, BigDecimal value) {
}
