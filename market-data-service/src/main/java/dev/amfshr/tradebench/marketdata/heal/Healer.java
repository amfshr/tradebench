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
 * nothing here claims to recover one. The week's allowance is checked before each run and
 * remembered across attempts until IG says it resets; a run that would take it below the reserve
 * stops the heal loud, leaving the rest open for a later attempt. A run reaching midnight leaves
 * its gap row to the next day's heal: the pump writes a gap across midnight only once the next
 * day's first bar seals, and recording half of it now would count those minutes twice.
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
    private final long reserve;
    // The last allowance IG reported, kept across attempts until it resets: a retry must not spend as
    // if the week were fresh (the doctrine review's finding 3).
    private long knownRemaining = Long.MAX_VALUE;
    private Instant knownUntil = Instant.MIN;

    /** @param reserve points the heal never spends — kept for manual heals and whatever else shares the week */
    public Healer(HealStore store, CandleSource candles, Clock clock, int maxRunMinutes, long reserve) {
        this.store = store;
        this.candles = candles;
        this.clock = clock;
        this.maxRunMinutes = maxRunMinutes;
        this.reserve = reserve;
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
        long spent = 0;
        for (Run run : runs) {
            long remaining = clock.wallInstant().isBefore(knownUntil) ? knownRemaining : Long.MAX_VALUE;
            if (remaining != Long.MAX_VALUE && remaining - run.minutes() < reserve) {
                throw new IllegalStateException("allowance too low for the next run (" + remaining + " points left, "
                        + run.minutes() + " minutes to ask, " + reserve + " kept in reserve) — healed " + filled.size()
                        + " minutes, the rest stay open");
            }
            PriceHistory history = candles.minuteCandles(epic, run.from(), run.to());
            List<Bar1m> bars = new ArrayList<>();
            for (PriceCandle candle : history.candles()) {
                bars.add(bar(epic, candle));
                filled.add(candle.snapshotTimeUtc());
            }
            store.writeHealed(bars);
            spent += bars.size();
            knownRemaining = history.allowance().remainingAllowance();
            knownUntil = clock.wallInstant().plusSeconds(history.allowance().allowanceExpirySeconds());
        }
        Set<Instant> healed = new TreeSet<>(filled);
        healed.addAll(store.healedMinutes(epic, from, to)); // an earlier attempt's bars count too
        Instant healedAt = clock.wallInstant();
        List<OpenGap> gaps = store.openGapsEnding(epic, from, to); // read once: closing them hides them
        for (OpenGap gap : gaps) {
            store.closeGap(gap.id(), healedAt, outcome(new Run(gap.from(), gap.to()), states, healed));
        }
        int rowsAdded = 0;
        for (Run run : runs) {
            boolean seenByThePump = gaps.stream().anyMatch(g -> !g.from().isAfter(run.from()) && !g.to().isBefore(run.to()));
            boolean reachesMidnight = run.to().equals(to.minusSeconds(60));
            if (!seenByThePump && !reachesMidnight) {
                store.recordHealedGap(epic, run, healedAt, outcome(run, states, healed));
                rowsAdded++;
            }
        }
        ObjectNode detail = MAPPER.createObjectNode();
        detail.put("expected", states.values().stream().filter(s -> s != MinuteState.CLOSED).count());
        detail.put("missing", missing.size());
        detail.put("runs", runs.size());
        detail.put("healedMinutes", filled.size());
        detail.put("pointsSpent", spent);
        if (clock.wallInstant().isBefore(knownUntil)) {
            detail.put("allowanceRemaining", knownRemaining);
        }
        detail.put("gapRowsClosed", gaps.size());
        detail.put("gapRowsAdded", rowsAdded);
        return detail;
    }

    /** A gap's outcome from its minutes inside this day: healed by IG (now or before), empty at IG, or never open. */
    static String outcome(Run gap, Map<Instant, MinuteState> states, Set<Instant> healed) {
        boolean anyExpected = false;
        for (Map.Entry<Instant, MinuteState> e : states.entrySet()) {
            if (!gap.contains(e.getKey())) {
                continue;
            }
            if (healed.contains(e.getKey())) {
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
