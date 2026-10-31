package dev.amfshr.tradebench.marketdata.events;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** A line's level, carried in its text and read back (E1-T16). */
class LogLineTest {

    @Test
    void aTaggedLineCarriesItsLevelAndKeepsItsText() {
        assertEquals("WARN sink unavailable", LogLine.warn("sink unavailable"));
        assertEquals("ERROR jobs: heal failed", LogLine.error("jobs: heal failed"));
        assertEquals(Severity.WARN, LogLine.severityOf(LogLine.warn("x")));
        assertEquals(Severity.ERROR, LogLine.severityOf(LogLine.error("x")));
        assertEquals("sink unavailable", LogLine.text(LogLine.warn("sink unavailable")));
        assertEquals("jobs: heal failed", LogLine.text(LogLine.error("jobs: heal failed")));
    }

    @Test
    void anEventsSeverityLeadsItsLineAndInfoLeavesItBare() {
        assertEquals("belt RECONNECT", LogLine.at(Severity.INFO, "belt RECONNECT"));
        assertEquals("WARN belt WATCHDOG_STALE", LogLine.at(Severity.WARN, "belt WATCHDOG_STALE"));
        assertEquals("ERROR belt IG_API_ERROR", LogLine.at(Severity.ERROR, "belt IG_API_ERROR"));
    }

    @Test
    void aFatalLineIsAnErrorByItsOwnWordAndKeepsIt() {
        assertEquals(Severity.ERROR, LogLine.severityOf("FATAL: recovery exhausted — the feed is dead"));
        assertEquals("FATAL: recovery exhausted", LogLine.text("FATAL: recovery exhausted"));
    }

    @Test
    void onlyTheExactTagCounts() {
        assertEquals(Severity.INFO, LogLine.severityOf("WARNING is not the tag"), "the tag is WARN and a space");
        assertEquals(Severity.INFO, LogLine.severityOf("ERRORS happen"), "the tag is ERROR and a space");
        assertEquals(Severity.INFO, LogLine.severityOf("capturing to … (migrated; advisory lock held)"));
        assertEquals("WARNING is not the tag", LogLine.text("WARNING is not the tag"));
    }
}
