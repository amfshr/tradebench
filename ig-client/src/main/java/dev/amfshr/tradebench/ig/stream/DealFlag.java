package dev.amfshr.tradebench.ig.stream;

import java.util.Set;

/**
 * IG's market-state flag ({@code DLG_FLAG}) read one way everywhere (E1-T15, ruled 2026-10-18): the
 * belt stands down and the heal counts a minute closed by the same word. Dealing means prices are
 * live — {@code DEAL}, and {@code DEALNOEDIT} and {@code CLOSINGSONLY}, under which they still stream.
 * Every other known flag is not dealing. A flag IG has never been seen to send counts as dealing,
 * so the watchdog keeps watching rather than going quiet on a word it does not know (P9).
 */
public final class DealFlag {

    private static final Set<String> NOT_DEALING =
            Set.of("CLOSED", "CALL", "EDIT", "AUCTION", "AUCTIONNOEDIT", "SUSPEND");

    private DealFlag() {
    }

    /** @param flag as parsed — stripped of the wire's padding */
    public static boolean isDealing(String flag) {
        return !NOT_DEALING.contains(flag);
    }
}
