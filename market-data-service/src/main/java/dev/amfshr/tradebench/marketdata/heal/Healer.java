package dev.amfshr.tradebench.marketdata.heal;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.amfshr.tradebench.core.domain.Bar1m;
import dev.amfshr.tradebench.core.domain.OhlcPrices;
import dev.amfshr.tradebench.core.time.Clock;
import dev.amfshr.tradebench.ig.rest.PriceCandle;
import dev.amfshr.tradebench.ig.rest.PriceHistory;
import dev.amfshr.tradebench.marketdata.jobs.DayPhase;
import dev.amfshr.tradebench.marketdata.jobs.MarketDay;

/**
 * The heal (E1-T6): every expected minute with no bar is asked of IG's history, run by contiguous
 * run, and each gap row in the day is closed with what IG said — {@code healed} (IG filled some of
 * it), {@code tickless_at_source} (IG had none of it: nobody traded) or {@code closed_at_source}
 * (every minute of it was closed by IG's own flag, so it was never a gap). Ticks are stream-only:
 * nothing here claims to recover one. The week's allowance is checked before each run; a run it
 * cannot cover stops the heal loud, leaving the rest open for a later attempt.
 */
public final class Healer implements DayPhase {

    public static final String NAME = "heal";
    static final String HEALED = "healed";
    static final String TICKLESS = "tickless_at_source";
    static final String CLOSED = "closed_at_source";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HealStore store;
    private final CandleSource candles;
    private final Clock clock;
    private final int maxRunMinutes;

    public Healer(HealStore store, CandleSource candles, Clock clock, int maxRunMinutes) {
        this.store = store;
        this.candles = candles;
        this.clock = clock;
        this.maxRunMinutes = maxRunMinutes;
    }

    @Override
    public String name() {
        return NAME;
    }

    /** Heal one market-day — the daily job's phase, and the entry point a manual heal calls (E9-T8). */
    @Override
    public ObjectNode run(MarketDay marketDay) throws Exception {
        String epic = marketDay.epic();
        Instant from = marketDay.day().atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = from.plusSeconds(86_400);
        @Nullable Instant first = store.firstRecord(epic);
        if (first == null || !first.isBefore(to)) {
            return MAPPER.createObjectNode().put("expected", 0).put("reason", "nothing recorded for this market yet");
        }
        Instant firstMinute = first.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        Map<Instant, MinuteState> states = DaySpans.classify(marketDay.day(), store.flagBefore(epic, from),
                store.flagChanges(epic, from, to), store.observed(epic, from, to));
        Set<Instant> present = store.barMinutes(epic, from, to);
        SortedSet<Instant> missing = new TreeSet<>();
        states.forEach((minute, state) -> {
            if (state != MinuteState.CLOSED && !present.contains(minute) && !minute.isBefore(firstMinute)) {
                missing.add(minute);
            }
        });
        List<Run> runs = Run.of(missing, maxRunMinutes);
        Set<Instant> filled = new TreeSet<>();
        long remaining = Long.MAX_VALUE;
        long spent = 0;
        for (Run run : runs) {
            if (remaining < run.minutes()) {
                throw new IllegalStateException("allowance too low for the next run (" + remaining + " points left, "
                        + run.minutes() + " minutes to ask) — healed " + filled.size() + " minutes, the rest stay open");
            }
            PriceHistory history = candles.minuteCandles(epic, run.from(), run.to());
            List<Bar1m> bars = new ArrayList<>();
            for (PriceCandle candle : history.candles()) {
                bars.add(bar(epic, candle));
                filled.add(candle.snapshotTimeUtc());
            }
            store.writeHealed(bars);
            spent += bars.size();
            remaining = history.allowance().remainingAllowance();
        }
        Instant healedAt = clock.wallInstant();
        List<OpenGap> gaps = store.openGapsEnding(epic, from, to); // read once: closing them hides them
        for (OpenGap gap : gaps) {
            store.closeGap(gap.id(), healedAt, outcome(new Run(gap.from(), gap.to()), states, filled));
        }
        int rowsAdded = 0;
        for (Run run : runs) {
            boolean seenByThePump = gaps.stream().anyMatch(g -> !g.from().isAfter(run.from()) && !g.to().isBefore(run.to()));
            if (!seenByThePump) {
                store.recordHealedGap(epic, run, healedAt, outcome(run, states, filled));
                rowsAdded++;
            }
        }
        ObjectNode detail = MAPPER.createObjectNode();
        detail.put("expected", states.values().stream().filter(s -> s != MinuteState.CLOSED).count());
        detail.put("missing", missing.size());
        detail.put("runs", runs.size());
        detail.put("healedMinutes", filled.size());
        detail.put("pointsSpent", spent);
        if (remaining != Long.MAX_VALUE) {
            detail.put("allowanceRemaining", remaining);
        }
        detail.put("gapRowsClosed", gaps.size());
        detail.put("gapRowsAdded", rowsAdded);
        return detail;
    }

    /** A gap's outcome from its minutes inside this day: filled by IG, empty at IG, or never open. */
    static String outcome(Run gap, Map<Instant, MinuteState> states, Set<Instant> filled) {
        boolean anyExpected = false;
        for (Map.Entry<Instant, MinuteState> e : states.entrySet()) {
            if (!gap.contains(e.getKey())) {
                continue;
            }
            if (filled.contains(e.getKey())) {
                return HEALED;
            }
            anyExpected |= e.getValue() != MinuteState.CLOSED;
        }
        return anyExpected ? TICKLESS : CLOSED;
    }

    private static Bar1m bar(String epic, PriceCandle c) {
        return new Bar1m(epic, c.snapshotTimeUtc(),
                new OhlcPrices(c.open().bid(), c.high().bid(), c.low().bid(), c.close().bid()),
                new OhlcPrices(c.open().ask(), c.high().ask(), c.low().ask(), c.close().ask()),
                c.lastTradedVolume());
    }
}
