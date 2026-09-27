package dev.amfshr.tradebench.ig.rest;

/**
 * REST v3 price resolutions. E1 heals with MINUTE only; higher timeframes are always derived
 * in our own code (there is no shortcut — §2.3), so requesting them from IG is reserved for
 * verification tooling.
 */
public enum Resolution {
    SECOND, MINUTE, MINUTE_5, MINUTE_15, MINUTE_30, HOUR, DAY
}
