package dev.amfshr.tradebench.marketdata.coverage;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Bar-grid gap detection (§4.1): completed 1m bars land on a minute grid; a new bar more
 * than one minute past the watermark names the missing span. Gaps are information, not
 * errors — facts about the world that T6's heal acts on. Dedupe on start-time (IG
 * re-sends completed candles); a late re-delivery of an OLDER bar can never regress the
 * watermark and fake a gap.
 */
public final class GapDetector {

    public record Gap(String epic, Instant gapFromUtc, Instant gapToUtc, long missingMinutes) {
    }

    private static final Duration MINUTE = Duration.ofMinutes(1);

    private final Map<String, Instant> watermarks = new HashMap<>();

    /** Feed every sealed bar; returns the gap this bar reveals, or null. */
    public @Nullable Gap onSealedBar(String epic, Instant startUtc) {
        Instant watermark = watermarks.get(epic);
        if (watermark == null) {
            watermarks.put(epic, startUtc);
            return null;
        }
        if (startUtc.compareTo(watermark) <= 0) {
            return null;
        }
        watermarks.put(epic, startUtc);
        long missing = Duration.between(watermark, startUtc).toMinutes() - 1;
        if (missing <= 0) {
            return null;
        }
        return new Gap(epic, watermark.plus(MINUTE), startUtc.minus(MINUTE), missing);
    }
}
