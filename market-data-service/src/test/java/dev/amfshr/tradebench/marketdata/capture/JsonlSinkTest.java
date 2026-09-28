package dev.amfshr.tradebench.marketdata.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.core.domain.Bar1m;
import dev.amfshr.tradebench.core.domain.OhlcPrices;
import dev.amfshr.tradebench.core.domain.Tick;

/** Golden lines: the capture file IS a wire contract (T4's importer reads it). */
class JsonlSinkTest {

    private static final String DAX = "IX.D.DAX.DAILY.IP";

    @Test
    void writesTheFourLineKindsExactly() {
        StringWriter out = new StringWriter();
        try (JsonlSink sink = new JsonlSink(out)) {
            sink.writeMeta("default-user", "ig-stream-demo", "capture-mac", List.of(DAX),
                    Instant.parse("2026-09-28T07:00:00Z"));
            sink.write(new Tick(DAX, Instant.parse("2026-09-25T14:57:03.250Z"),
                    new BigDecimal("24510.5"), new BigDecimal("24511.7")));
            sink.write(new Bar1m(DAX, Instant.parse("2026-09-25T14:57:00Z"),
                    new OhlcPrices(new BigDecimal("24510.5"), new BigDecimal("24514.8"),
                            new BigDecimal("24508.3"), new BigDecimal("24512.0")),
                    new OhlcPrices(new BigDecimal("24511.7"), new BigDecimal("24516.0"),
                            new BigDecimal("24509.5"), new BigDecimal("24513.2")),
                    321L));
            sink.write(new CaptureQueues.StateChange(DAX,
                    Instant.parse("2026-09-25T16:30:00Z"), "CLOSED"));
        }

        assertEquals("""
                {"kind":"meta","user":"default-user","source":"ig-stream-demo",\
                "instance":"capture-mac","startedUtc":"2026-09-28T07:00:00Z",\
                "epics":["IX.D.DAX.DAILY.IP"]}
                {"kind":"tick","epic":"IX.D.DAX.DAILY.IP","utc":"2026-09-25T14:57:03.250Z",\
                "bid":24510.5,"ask":24511.7}
                {"kind":"bar","epic":"IX.D.DAX.DAILY.IP","startUtc":"2026-09-25T14:57:00Z",\
                "bid":{"o":24510.5,"h":24514.8,"l":24508.3,"c":24512.0},\
                "ask":{"o":24511.7,"h":24516.0,"l":24509.5,"c":24513.2},"vol":321}
                {"kind":"state","epic":"IX.D.DAX.DAILY.IP","utc":"2026-09-25T16:30:00Z",\
                "dealFlag":"CLOSED"}
                """, out.toString());
    }

    @Test
    void barWithoutVolumeOmitsTheField() {
        StringWriter out = new StringWriter();
        try (JsonlSink sink = new JsonlSink(out)) {
            sink.write(new Bar1m(DAX, Instant.parse("2026-09-25T14:57:00Z"),
                    new OhlcPrices(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                            BigDecimal.ONE),
                    new OhlcPrices(BigDecimal.TWO, BigDecimal.TWO, BigDecimal.TWO,
                            BigDecimal.TWO),
                    null));
        }
        assertEquals("{\"kind\":\"bar\",\"epic\":\"IX.D.DAX.DAILY.IP\","
                + "\"startUtc\":\"2026-09-25T14:57:00Z\","
                + "\"bid\":{\"o\":1,\"h\":1,\"l\":1,\"c\":1},"
                + "\"ask\":{\"o\":2,\"h\":2,\"l\":2,\"c\":2}}\n", out.toString());
    }
}
