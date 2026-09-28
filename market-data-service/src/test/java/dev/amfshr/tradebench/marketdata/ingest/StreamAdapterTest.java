package dev.amfshr.tradebench.marketdata.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.core.domain.Bar1m;
import dev.amfshr.tradebench.core.domain.OhlcPrices;
import dev.amfshr.tradebench.core.domain.Tick;
import dev.amfshr.tradebench.ig.stream.Ohlc;
import dev.amfshr.tradebench.ig.stream.SealedBarUpdate;
import dev.amfshr.tradebench.ig.stream.TickUpdate;

class StreamAdapterTest {

    private static final String DAX = "IX.D.DAX.DAILY.IP";

    @Test
    void mapsATickAndDropsTheDealFlag() {
        Tick tick = StreamAdapter.toDomain(new TickUpdate(DAX,
                Instant.parse("2026-09-25T14:57:03.250Z"),
                new BigDecimal("24510.5"), new BigDecimal("24511.7"), "DEAL"));

        assertEquals(new Tick(DAX, Instant.parse("2026-09-25T14:57:03.250Z"),
                new BigDecimal("24510.5"), new BigDecimal("24511.7")), tick);
    }

    @Test
    void mapsABarKeepingBidAndAskSidesStraight() {
        Bar1m bar = StreamAdapter.toDomain(new SealedBarUpdate(DAX,
                Instant.parse("2026-09-25T14:57:00Z"),
                new Ohlc(new BigDecimal("24510.5"), new BigDecimal("24514.8"),
                        new BigDecimal("24508.3"), new BigDecimal("24512.0")),
                new Ohlc(new BigDecimal("24511.7"), new BigDecimal("24516.0"),
                        new BigDecimal("24509.5"), new BigDecimal("24513.2")),
                321L));

        assertEquals(new Bar1m(DAX, Instant.parse("2026-09-25T14:57:00Z"),
                new OhlcPrices(new BigDecimal("24510.5"), new BigDecimal("24514.8"),
                        new BigDecimal("24508.3"), new BigDecimal("24512.0")),
                new OhlcPrices(new BigDecimal("24511.7"), new BigDecimal("24516.0"),
                        new BigDecimal("24509.5"), new BigDecimal("24513.2")),
                321L), bar);
    }
}
