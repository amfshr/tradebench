package dev.amfshr.tradebench.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class Bar1mTest {

    private static final Bar1m BAR = new Bar1m("IX.D.DAX.DAILY.IP",
            Instant.parse("2026-09-25T14:57:00Z"),
            new OhlcPrices(new BigDecimal("24510.5"), new BigDecimal("24514.8"),
                    new BigDecimal("24508.3"), new BigDecimal("24512.0")),
            new OhlcPrices(new BigDecimal("24511.7"), new BigDecimal("24516.0"),
                    new BigDecimal("24509.5"), new BigDecimal("24513.2")),
            321L);

    @Test
    void sealsExactlyOneMinuteAfterItsStart() {
        assertEquals(Instant.parse("2026-09-25T14:58:00Z"), BAR.sealInstant());
    }

    @Test
    void dataTimeIsTheSealInstantNotTheStart() {
        // A bar "happens" when it completes — decisions on data time (D38 doctrine).
        assertEquals(Instant.parse("2026-09-25T14:58:00Z"), BAR.dataTime());
    }

    @Test
    void midIsThePerFieldAverageExactly() {
        // Anchors computed by hand from the literals above — per-field, exact decimals.
        assertEquals(new OhlcPrices(new BigDecimal("24511.1"), new BigDecimal("24515.4"),
                new BigDecimal("24508.9"), new BigDecimal("24512.6")), BAR.mid());
    }

    @Test
    void midStaysExactWhenTheSumIsOdd() {
        Bar1m thin = new Bar1m("E", Instant.EPOCH,
                new OhlcPrices(new BigDecimal("1.1"), new BigDecimal("1.1"),
                        new BigDecimal("1.1"), new BigDecimal("1.1")),
                new OhlcPrices(new BigDecimal("1.2"), new BigDecimal("1.2"),
                        new BigDecimal("1.2"), new BigDecimal("1.2")),
                null);
        // (1.1 + 1.2) / 2 = 1.15 — division by two terminates; no rounding, no exception.
        assertEquals(new BigDecimal("1.15"), thin.mid().open());
    }
}
