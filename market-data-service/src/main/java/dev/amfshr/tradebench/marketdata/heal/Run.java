package dev.amfshr.tradebench.marketdata.heal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;

/** A contiguous run of minute starts, both ends inclusive. */
public record Run(Instant from, Instant to) {

    public long minutes() {
        return Duration.between(from, to).toMinutes() + 1;
    }

    /** Group minute starts into maximal contiguous runs, none longer than {@code maxMinutes}. */
    public static List<Run> of(SortedSet<Instant> minuteStarts, int maxMinutes) {
        List<Run> runs = new ArrayList<>();
        Instant from = null;
        Instant to = null;
        for (Instant m : minuteStarts) {
            if (from != null && m.equals(to.plusSeconds(60)) && Duration.between(from, m).toMinutes() < maxMinutes) {
                to = m;
                continue;
            }
            if (from != null) {
                runs.add(new Run(from, to));
            }
            from = m;
            to = m;
        }
        if (from != null) {
            runs.add(new Run(from, to));
        }
        return runs;
    }

    boolean contains(Instant minute) {
        return !minute.isBefore(from) && !minute.isAfter(to);
    }
}
