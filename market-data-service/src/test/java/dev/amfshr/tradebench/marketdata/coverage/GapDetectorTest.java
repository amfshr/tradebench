package dev.amfshr.tradebench.marketdata.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.marketdata.coverage.GapDetector.Gap;

class GapDetectorTest {

    private static final String DAX = "IX.D.DAX.DAILY.IP";

    private GapDetector detector;

    @BeforeEach
    void setUp() {
        detector = new GapDetector();
    }

    private static Instant t(String hhmm) {
        return Instant.parse("2026-09-28T" + hhmm + ":00Z");
    }

    @Test
    void contiguousBarsProduceNoGaps() {
        assertNull(detector.onSealedBar(DAX, t("17:00")));
        assertNull(detector.onSealedBar(DAX, t("17:01")));
        assertNull(detector.onSealedBar(DAX, t("17:02")));
    }

    @Test
    void theLiveOutageSpecimenIsNamedExactly() {
        // Today's real incident: bars 17:02 then 17:15 — 12 minutes missing.
        detector.onSealedBar(DAX, t("17:02"));
        assertEquals(new Gap(DAX, t("17:03"), t("17:14"), 12),
                detector.onSealedBar(DAX, t("17:15")));
    }

    @Test
    void redeliveredCandleIsDeduplicated() {
        detector.onSealedBar(DAX, t("17:00"));
        assertNull(detector.onSealedBar(DAX, t("17:00")), "IG re-sends completed candles");
    }

    @Test
    void lateOlderBarCannotRegressTheWatermarkAndFakeAGap() {
        detector.onSealedBar(DAX, t("17:00"));
        detector.onSealedBar(DAX, t("17:01"));
        assertNull(detector.onSealedBar(DAX, t("16:58")));
        assertNull(detector.onSealedBar(DAX, t("17:02")),
                "watermark still 17:01 — no phantom gap after the late redelivery");
    }

    @Test
    void marketsAreIndependent() {
        detector.onSealedBar(DAX, t("17:00"));
        assertNull(detector.onSealedBar("IX.D.NASDAQ.CASH.IP", t("17:05")),
                "first bar for another epic sets its own watermark, names no gap");
    }
}
