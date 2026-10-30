package dev.amfshr.tradebench.marketdata.heal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.amfshr.tradebench.core.domain.Bar1m;
import dev.amfshr.tradebench.ig.rest.Allowance;
import dev.amfshr.tradebench.ig.rest.PriceCandle;
import dev.amfshr.tradebench.ig.rest.PriceHistory;
import dev.amfshr.tradebench.ig.rest.PricePoint;
import dev.amfshr.tradebench.marketdata.jobs.MarketDay;
import dev.amfshr.tradebench.marketdata.testutil.FakeClock;

/** E1-T6 ③: the heal asks for exactly the missing expected minutes and closes each gap with IG's answer. */
class HealerTest {

    private static final String DAX = "IX.D.DAX.DAILY.IP";
    private static final LocalDate D = LocalDate.of(2026, 10, 15);
    private static final MarketDay DAX_D = new MarketDay(DAX, D);

    private final FakeStore store = new FakeStore();
    private final FakeIg ig = new FakeIg();
    private final FakeClock clock = new FakeClock(Instant.parse("2026-10-16T00:10:00Z"));
    private final Healer healer = new Healer(store, ig, clock, 720, 0);

    @Test
    void aGapIgCanFillIsHealedFromItsHistoryAndItsRowClosed() throws Exception {
        store.openAllDay();
        store.missing("10:00", "10:01", "10:02");
        store.gap(7, "10:00", "10:02");
        ig.has("10:00", "10:02"); // nobody traded at 10:01

        ObjectNode detail = healer.run(DAX_D);

        assertEquals(List.of(at("10:00") + "→" + at("10:02")), ig.asked, "one run, exactly the missing minutes");
        assertEquals(List.of(at("10:00"), at("10:02")), store.healed.stream().map(Bar1m::startUtc).toList());
        assertEquals(Map.of(7L, Healer.HEALED), store.closed);
        assertTrue(store.added.isEmpty(), "the pump already recorded this gap");
        assertEquals(3, detail.get("missing").asInt());
        assertEquals(2, detail.get("healedMinutes").asInt());
    }

    @Test
    void aGapIgHasNoCandlesForIsTicklessAtSource() throws Exception {
        store.openAllDay();
        store.missing("03:10", "03:11");
        store.gap(8, "03:10", "03:11");

        healer.run(DAX_D);

        assertEquals(Map.of(8L, Healer.TICKLESS), store.closed, "IG had none: nobody traded, not a loss of ours");
    }

    @Test
    void aGapAcrossIgsOwnClosureIsNeverAskedForAndClosedAsClosedAtSource() throws Exception {
        store.flagAtStart = "DEAL";
        store.changes.add(new FlagChange(at("21:00"), "CLOSED"));
        store.changes.add(new FlagChange(at("22:00"), "DEAL"));
        store.observeAllDay();
        store.missing("21:00", "21:30", "21:59");
        store.gap(9, "21:00", "21:59"); // the pump saw sealed bars at 20:59 and 22:00

        healer.run(DAX_D);

        assertTrue(ig.asked.isEmpty(), "a closed minute is never asked for — no points spent on IG's own closure");
        assertEquals(Map.of(9L, Healer.CLOSED), store.closed);
    }

    @Test
    void aWindowTheCollectorWasDownForIsAskedOfIgAndRecordedAsAGapOfItsOwn() throws Exception {
        store.flagAtStart = "DEAL";
        store.observed.add(new Window(at("00:00"), at("12:29").plusSeconds(59)));
        store.observed.add(new Window(at("13:00"), at("23:59").plusSeconds(59)));
        store.everyMinuteHasABarExceptBetween("12:30", "12:59");
        store.processRuns.clear(); // the process died at 12:29 and was rebooted at 13:00
        store.processRuns.add(new Window(at("00:00"), at("12:29").plusSeconds(59)));
        store.processRuns.add(new Window(at("13:00"), Instant.parse("9999-01-01T00:00:00Z")));
        ig.has("12:30", "12:45");

        var detail = healer.run(DAX_D);

        assertEquals(List.of(at("12:30") + "→" + at("12:59")), ig.asked);
        assertEquals(1, detail.get("gapRowsAdded").asInt());
        assertEquals(30, detail.get("gapMinutesAdded").asLong(), "12:30–12:59: the row's minutes, for gap minutes by origin (E1-T16)");
        assertEquals(List.of(at("12:30") + "→" + at("12:59") + " " + Healer.HEALED), store.added,
                "the pump could not see a gap across its own restart: the heal records it");
    }

    @Test
    void aRunTheAllowanceCannotCoverStopsTheHealLoudLeavingTheRestOpen() throws Exception {
        store.openAllDay();
        store.missing("01:00", "05:00", "05:01");
        store.gap(10, "01:00", "01:00");
        store.gap(11, "05:00", "05:01");
        ig.has("01:00");
        ig.remainingAfterEachCall = 1; // after the first run, one point left; the next run needs two

        IllegalStateException stopped = assertThrows(IllegalStateException.class, () -> healer.run(DAX_D));

        assertTrue(stopped.getMessage().contains("allowance too low"), stopped.getMessage());
        assertEquals(1, ig.asked.size(), "the second run was never asked");
        assertEquals(List.of(at("01:00")), store.healed.stream().map(Bar1m::startUtc).toList(), "what was healed stays");
        assertTrue(store.closed.isEmpty(), "rows stay open for the retry");
    }

    @Test
    void aRetryAfterAPartialHealStillCallsTheEarlierHealedGapHealed() throws Exception {
        // the doctrine review's finding 2: gap 10 heals, the allowance stops gap 11; on the retry gap
        // 10's minutes are present bars — healed by the first attempt — and must not read as tickless
        store.openAllDay();
        store.missing("01:00", "05:00", "05:01");
        store.gap(10, "01:00", "01:00");
        store.gap(11, "05:00", "05:01");
        ig.has("01:00", "05:00", "05:01");
        ig.remainingAfterEachCall = 1;
        assertThrows(IllegalStateException.class, () -> healer.run(DAX_D));

        ig.remainingAfterEachCall = 9_000;
        clock.advance(java.time.Duration.ofDays(8)); // IG's allowance has reset since
        healer.run(DAX_D);

        assertEquals(Map.of(10L, Healer.HEALED, 11L, Healer.HEALED), store.closed);
    }

    @Test
    void theAllowanceIsRememberedAcrossAttemptsUntilItResets() throws Exception {
        // the doctrine review's finding 3: a retry must not spend as if the week were fresh
        store.openAllDay();
        store.missing("01:00", "05:00", "05:01");
        store.gap(10, "01:00", "01:00");
        store.gap(11, "05:00", "05:01");
        ig.has("01:00");
        ig.remainingAfterEachCall = 1;
        assertThrows(IllegalStateException.class, () -> healer.run(DAX_D));
        int asked = ig.asked.size();

        clock.advance(java.time.Duration.ofHours(1)); // the runner's retry, the same week
        assertThrows(IllegalStateException.class, () -> healer.run(DAX_D));

        assertEquals(asked, ig.asked.size(), "the second attempt refuses before asking IG anything");
    }

    @Test
    void aRunExactlyCoveredByWhatIsLeftAboveTheReserveProceeds() throws Exception {
        Healer reserving = new Healer(store, ig, clock, 720, 100);
        store.openAllDay();
        store.missing("01:00", "05:00", "05:01");
        ig.has("01:00", "05:00", "05:01");
        ig.remainingAfterEachCall = 102; // 102 − 2 minutes = the reserve exactly

        reserving.run(DAX_D);

        assertEquals(2, ig.asked.size(), "equal to the reserve is enough; below it is not");
    }

    @Test
    void aGapThePumpCouldSeeIsLeftToThePumpWhateverDayItEndsOn() throws Exception {
        // the re-review's N1 and N2: one process ran before 23:50 and past midnight, so the pump writes
        // this gap when the next day's first bar seals — recording 23:50–23:59 now would count it twice
        store.everyMinuteHasABarExceptBetween("23:50", "23:59");
        store.flagAtStart = "DEAL";
        ig.has("23:55");

        healer.run(DAX_D);

        assertEquals(List.of(at("23:55")), store.healed.stream().map(Bar1m::startUtc).toList(), "the bars are healed");
        assertTrue(store.added.isEmpty(), "and the row is the pump's");
    }

    @Test
    void aGapAcrossARestartIsRecordedByTheHealEvenAtMidnight() throws Exception {
        // the re-review's N2: the process died at 23:30 and came back after midnight — the new process has
        // no watermark from before the gap, so the pump will never write it; the heal must
        store.everyMinuteHasABarExceptBetween("23:30", "23:59");
        store.flagAtStart = "DEAL";
        store.processRuns.clear();
        store.processRuns.add(new Window(at("00:00"), at("23:29").plusSeconds(59)));
        store.processRuns.add(new Window(Instant.parse("2026-10-16T00:30:00Z"), Instant.parse("9999-01-01T00:00:00Z")));
        ig.has("23:40");

        healer.run(DAX_D);

        assertEquals(List.of(at("23:30") + "→" + at("23:59") + " " + Healer.HEALED), store.added);
    }

    @Test
    void aRestartGapsRowIsRecordedAsSoonAsItIsHealedSoALaterStopCannotLoseIt() throws Exception {
        // the re-review's N4: run one heals a restart gap, then the allowance stops run two; on the retry run
        // one's minutes are present bars and form no run — so its row must already exist
        store.everyMinuteHasABarExceptBetween("01:00", "01:00");
        store.bars.remove(at("05:00"));
        store.bars.remove(at("05:01"));
        store.flagAtStart = "DEAL";
        store.processRuns.clear();
        store.processRuns.add(new Window(at("00:00"), at("00:59").plusSeconds(59)));
        store.processRuns.add(new Window(at("01:01"), Instant.parse("9999-01-01T00:00:00Z")));
        store.gap(11, "05:00", "05:01");
        ig.has("01:00", "05:00");
        ig.remainingAfterEachCall = 1;

        assertThrows(IllegalStateException.class, () -> healer.run(DAX_D));

        assertEquals(List.of(at("01:00") + "→" + at("01:00") + " " + Healer.HEALED), store.added,
                "recorded straight after its run, before the stop");
    }

    @Test
    void minutesBeforeTheCollectorsFirstRecordAreNeverAskedFor() throws Exception {
        // the first deploy: the database starts empty, so the morning before the boot has no bars,
        // no flags and no observation — asking for it would spend the week's allowance on history
        // nobody claimed to capture
        store.first = at("14:30").plusSeconds(20);
        store.flagAtStart = null;
        store.changes.add(new FlagChange(store.first, "DEAL"));
        store.observed.add(new Window(store.first, at("23:59").plusSeconds(59)));
        for (Instant m = at("14:32"); !m.isAfter(at("23:59")); m = m.plusSeconds(60)) {
            store.bars.add(m); // streamed from 14:32; 14:30 and 14:31 missing
        }

        healer.run(DAX_D);

        assertEquals(List.of(at("14:30") + "→" + at("14:31")), ig.asked,
                "the first record's own minute onward — never the empty morning before the boot");
    }

    @Test
    void aMarketWithNoRecordAtAllIsNotHealed() throws Exception {
        store.first = null;

        ObjectNode detail = healer.run(DAX_D);

        assertTrue(ig.asked.isEmpty());
        assertEquals(0, detail.get("expected").asInt());
    }

    private static Instant at(String hhmm) {
        return Instant.parse(D + "T" + hhmm + ":00Z");
    }

    private static final class FakeIg implements CandleSource {
        final List<String> asked = new ArrayList<>();
        final Set<Instant> candles = new HashSet<>();
        long remainingAfterEachCall = 9_000;

        void has(String... minutes) {
            for (String m : minutes) {
                candles.add(at(m));
            }
        }

        @Override
        public PriceHistory minuteCandles(String epic, Instant from, Instant to) {
            asked.add(from + "→" + to);
            List<PriceCandle> served = new ArrayList<>();
            for (Instant m = from; !m.isAfter(to); m = m.plusSeconds(60)) {
                if (candles.contains(m)) {
                    PricePoint p = new PricePoint(BigDecimal.ONE, BigDecimal.TWO, null);
                    served.add(new PriceCandle(m, p, p, p, p, null));
                }
            }
            return new PriceHistory(served, new Allowance(remainingAfterEachCall, 10_000, 600_000));
        }
    }

    private static final class FakeStore implements HealStore {
        @Nullable String flagAtStart;
        @Nullable Instant first = Instant.parse("2026-10-01T00:00:00Z");
        final List<FlagChange> changes = new ArrayList<>();
        final List<Window> observed = new ArrayList<>();
        final List<Window> processRuns = new ArrayList<>(List.of(new Window(Instant.EPOCH, Instant.parse("9999-01-01T00:00:00Z"))));
        final Set<Instant> bars = new HashSet<>();
        final List<OpenGap> gaps = new ArrayList<>();
        final List<Bar1m> healed = new ArrayList<>();
        final Map<Long, String> closed = new LinkedHashMap<>();
        final List<String> added = new ArrayList<>();

        void observeAllDay() {
            observed.add(new Window(at("00:00"), at("23:59").plusSeconds(59)));
            for (int i = 0; i < 1440; i++) {
                bars.add(at("00:00").plusSeconds(60L * i));
            }
        }

        void openAllDay() {
            flagAtStart = "DEAL";
            observeAllDay();
        }

        void missing(String... minutes) {
            for (String m : minutes) {
                bars.remove(at(m));
            }
        }

        void everyMinuteHasABarExceptBetween(String from, String to) {
            observeAllDay();
            for (Instant m = at(from); !m.isAfter(at(to)); m = m.plusSeconds(60)) {
                bars.remove(m);
            }
        }

        void gap(long id, String from, String to) {
            gaps.add(new OpenGap(id, at(from), at(to)));
        }

        @Override
        public @Nullable Instant firstRecord(String epic) {
            return first;
        }

        @Override
        public @Nullable String flagBefore(String epic, Instant at) {
            return flagAtStart;
        }

        @Override
        public List<FlagChange> flagChanges(String epic, Instant from, Instant to) {
            return changes;
        }

        @Override
        public List<Window> processRuns(String epic, Instant from, Instant to) {
            return processRuns;
        }

        @Override
        public List<Window> observed(String epic, Instant from, Instant to) {
            return observed;
        }

        @Override
        public Set<Instant> barMinutes(String epic, Instant from, Instant to) {
            return bars;
        }

        @Override
        public Set<Instant> healedMinutes(String epic, Instant from, Instant to) {
            Set<Instant> minutes = new HashSet<>();
            healed.forEach(b -> minutes.add(b.startUtc()));
            return minutes;
        }

        @Override
        public void writeHealed(List<Bar1m> written) {
            healed.addAll(written);
            written.forEach(b -> bars.add(b.startUtc())); // a healed bar is a present bar from now on
        }

        @Override
        public List<OpenGap> openGapsEnding(String epic, Instant from, Instant to) {
            return gaps.stream().filter(g -> !closed.containsKey(g.id())).toList();
        }

        @Override
        public void closeGap(long id, Instant healedAt, String outcome) {
            closed.put(id, outcome);
        }

        @Override
        public void recordHealedGap(String epic, Run run, Instant healedAt, String outcome) {
            added.add(run.from() + "→" + run.to() + " " + outcome);
        }
    }
}
