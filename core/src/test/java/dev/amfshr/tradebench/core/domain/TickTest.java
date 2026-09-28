package dev.amfshr.tradebench.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class TickTest {

    @Test
    void dataTimeIsTheTickTimestamp() {
        Tick tick = new Tick("IX.D.DAX.DAILY.IP", Instant.parse("2026-09-25T14:57:03.250Z"),
                new BigDecimal("24510.5"), new BigDecimal("24511.7"));

        assertEquals(Instant.parse("2026-09-25T14:57:03.250Z"), tick.dataTime());
    }
}
