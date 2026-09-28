package dev.amfshr.tradebench.ig.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Field-map anchors from the playbook §2.2 and docs/reference (epoch-ms strings, padding). */
class StreamParsersTest {

    private static final String DAX = "IX.D.DAX.DAILY.IP";

    // 2026-09-25T14:57:03.250Z as epoch millis.
    private static final String TICK_MS = "1790348223250";
    // 2026-09-25T14:57:00Z as epoch millis.
    private static final String BAR_START_MS = "1790348220000";

    private static Map<String, String> tickFields() {
        Map<String, String> fields = new HashMap<>();
        fields.put("TIMESTAMP", TICK_MS);
        fields.put("BIDPRICE1", "24510.5");
        fields.put("ASKPRICE1", "24511.7");
        fields.put("DLG_FLAG", "DEAL ");
        return fields;
    }

    private static Map<String, String> sealedBarFields() {
        Map<String, String> fields = new HashMap<>();
        fields.put("UTM", BAR_START_MS);
        fields.put("CONS_END", "1");
        fields.put("BID_OPEN", "24510.5");
        fields.put("BID_HIGH", "24514.8");
        fields.put("BID_LOW", "24508.3");
        fields.put("BID_CLOSE", "24512.0");
        fields.put("OFR_OPEN", "24511.7");
        fields.put("OFR_HIGH", "24516.0");
        fields.put("OFR_LOW", "24509.5");
        fields.put("OFR_CLOSE", "24513.2");
        fields.put("LTV", "321");
        return fields;
    }

    @Test
    void parsesATickExactlyAndStripsTheWirePaddedFlag() {
        TickUpdate tick = StreamParsers.parseTick(DAX, tickFields());

        assertEquals(new TickUpdate(DAX, Instant.parse("2026-09-25T14:57:03.250Z"),
                new BigDecimal("24510.5"), new BigDecimal("24511.7"), "DEAL"), tick);
    }

    @Test
    void tickMissingAnyFieldIsDroppedNotThrown() {
        for (String required : new String[] {"TIMESTAMP", "BIDPRICE1", "ASKPRICE1", "DLG_FLAG"}) {
            Map<String, String> fields = tickFields();
            fields.remove(required);
            assertNull(StreamParsers.parseTick(DAX, fields), "should drop without " + required);
        }
    }

    @Test
    void tickWithGarbageNumberIsDroppedNotThrown() {
        Map<String, String> fields = tickFields();
        fields.put("BIDPRICE1", "not-a-price");
        assertNull(StreamParsers.parseTick(DAX, fields));
    }

    @Test
    void inProgressCandleIsExpectedNotSealed() {
        Map<String, String> fields = sealedBarFields();
        fields.put("CONS_END", "0");
        assertFalse(StreamParsers.isSealed(fields));
        fields.remove("CONS_END");
        assertFalse(StreamParsers.isSealed(fields));
    }

    @Test
    void sealedFlagIsRecognised() {
        assertTrue(StreamParsers.isSealed(sealedBarFields()));
    }

    @Test
    void parsesASealedBarExactly() {
        SealedBarUpdate bar = StreamParsers.parseSealedBar(DAX, sealedBarFields());

        assertEquals(new SealedBarUpdate(DAX, Instant.parse("2026-09-25T14:57:00Z"),
                new Ohlc(new BigDecimal("24510.5"), new BigDecimal("24514.8"),
                        new BigDecimal("24508.3"), new BigDecimal("24512.0")),
                new Ohlc(new BigDecimal("24511.7"), new BigDecimal("24516.0"),
                        new BigDecimal("24509.5"), new BigDecimal("24513.2")),
                321L), bar);
    }

    @Test
    void barWithoutVolumeStillParses() {
        Map<String, String> fields = sealedBarFields();
        fields.remove("LTV");
        SealedBarUpdate bar = StreamParsers.parseSealedBar(DAX, fields);
        assertNull(bar.tickVolume());
    }

    @Test
    void barMissingAnyPriceCornerIsDroppedNotThrown() {
        Map<String, String> fields = sealedBarFields();
        fields.remove("OFR_LOW");
        assertNull(StreamParsers.parseSealedBar(DAX, fields));
    }
}
