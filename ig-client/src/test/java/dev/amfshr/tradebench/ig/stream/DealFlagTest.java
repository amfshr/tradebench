package dev.amfshr.tradebench.ig.stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The vocabulary from IG's reference (docs/reference/ig-api-reference-summary.md), word by word. */
class DealFlagTest {

    @Test
    void pricesStreamUnderTheThreeDealingFlags() {
        for (String flag : new String[] {"DEAL", "DEALNOEDIT", "CLOSINGSONLY"}) {
            assertTrue(DealFlag.isDealing(flag), flag);
        }
    }

    @Test
    void everyOtherKnownFlagIsNotDealing() {
        for (String flag : new String[] {"CLOSED", "CALL", "EDIT", "AUCTION", "AUCTIONNOEDIT", "SUSPEND"}) {
            assertFalse(DealFlag.isDealing(flag), flag);
        }
    }

    @Test
    void aFlagNeverSeenBeforeCountsAsDealingSoTheWatchdogKeepsWatching() {
        assertTrue(DealFlag.isDealing("SOMETHINGNEW"));
    }
}
