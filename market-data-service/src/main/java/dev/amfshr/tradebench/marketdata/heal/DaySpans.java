package dev.amfshr.tradebench.marketdata.heal;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import dev.amfshr.tradebench.ig.stream.DealFlag;

/**
 * Every minute of a market's UTC day, classified from the record alone (D25 amended 2026-10-16).
 * A minute is {@link MinuteState#UNKNOWN} unless one observation window covers all of it; an
 * observed minute is {@link MinuteState#OPEN} if IG's flag said dealing ({@link DealFlag} — the word the
 * belt stands down by, E1-T15) at any point in it, {@link MinuteState#CLOSED} if it said something else
 * throughout, and unknown if IG had not yet said anything.
 */
public final class DaySpans {

    private static final Duration MINUTE = Duration.ofMinutes(1);

    private DaySpans() {
    }

    /** @param flagAtStart the flag in force as the day began (the last change before it), if any
     *  @param changes the day's flag changes, oldest first */
    public static Map<Instant, MinuteState> classify(LocalDate day, @Nullable String flagAtStart,
            List<FlagChange> changes, List<Window> observed) {
        Map<Instant, MinuteState> minutes = new LinkedHashMap<>();
        Instant start = day.atStartOfDay().toInstant(ZoneOffset.UTC);
        @Nullable String inForce = flagAtStart;
        int next = 0;
        for (int i = 0; i < 1440; i++) {
            Instant m = start.plus(MINUTE.multipliedBy(i));
            Instant end = m.plus(MINUTE);
            while (next < changes.size() && !changes.get(next).at().isAfter(m)) {
                inForce = changes.get(next++).flag();
            }
            boolean deal = dealing(inForce);
            @Nullable String during = inForce;
            for (int j = next; j < changes.size() && changes.get(j).at().isBefore(end); j++) {
                during = changes.get(j).flag();
                deal |= dealing(during);
            }
            boolean watched = observed.stream().anyMatch(w -> w.covers(m, end));
            MinuteState state;
            if (!watched || (inForce == null && during == null)) {
                state = MinuteState.UNKNOWN;
            } else {
                state = deal ? MinuteState.OPEN : MinuteState.CLOSED;
            }
            minutes.put(m, state);
        }
        return minutes;
    }

    private static boolean dealing(@Nullable String flag) {
        return flag != null && DealFlag.isDealing(flag);
    }
}
