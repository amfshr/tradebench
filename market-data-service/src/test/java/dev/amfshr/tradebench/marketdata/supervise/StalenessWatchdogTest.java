package dev.amfshr.tradebench.marketdata.supervise;

import java.time.Duration;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.marketdata.supervise.StalenessWatchdog.Action;
import dev.amfshr.tradebench.marketdata.supervise.StalenessWatchdog.Remedy;
import dev.amfshr.tradebench.marketdata.supervise.StalenessWatchdog.Signal;

/**
 * The §3.4 scenario scripts on a faithful ~1s evaluation cadence (the design's contract —
 * coarse time jumps would trip the freeze detector, by design). Time is plain longs.
 */
class StalenessWatchdogTest {

    private static final String DAX = "IX.D.DAX.DAILY.IP";
    private static final String NDX = "IX.D.NASDAQ.CASH.IP";
    private static final long S = 1_000_000_000L;

    private record Fired(long second, Remedy remedy) {
    }

    private StalenessWatchdog dog;
    private long clockSecond;
    private final List<Fired> fired = new ArrayList<>();

    @BeforeEach
    void setUp() {
        dog = new StalenessWatchdog(Tuning.playbook(), Duration.ofSeconds(1));
        dog.track(DAX, 0);
        clockSecond = 0;
        fired.clear();
    }

    /** Advance in 1s rounds to targetSecond inclusive, collecting every remedy. */
    private void runTo(long targetSecond) {
        while (clockSecond < targetSecond) {
            clockSecond++;
            for (Remedy r : dog.evaluate(clockSecond * S, clockSecond * 1000)) {
                fired.add(new Fired(clockSecond, r));
            }
        }
    }

    @Test
    void tickSilenceFiresAtExactlyNinetySeconds() {
        runTo(200);
        assertEquals(90, fired.get(0).second(),
                "the 90s boundary is exact — in-window DAX runs ~90 ticks/min");
        assertEquals(new Remedy(DAX, Action.RESUBSCRIBE, Signal.TICK_SILENT),
                fired.get(0).remedy());
    }

    @Test
    void barSilenceWhileTicksFlowFiresAtExactly210Seconds() {
        for (long t = 10; t <= 400; t += 10) {
            dog.onTick(DAX, t * S);   // PRICE alive throughout
        }
        runTo(250);
        assertEquals(210, fired.get(0).second(),
                "the only way to see a dead CHART while PRICE lives");
        assertEquals(Signal.BAR_SILENT_TICKS_FLOWING, fired.get(0).remedy().signal());
    }

    @Test
    void whenBothAreSilentTheSignalIsTickSilentNotBarSilent() {
        runTo(300);
        assertEquals(Signal.TICK_SILENT, fired.get(0).remedy().signal(),
                "a genuinely dead feed must not be misread as a dead CHART subscription");
    }

    @Test
    void closedFlagStandsTheWatchdogDown() {
        dog.onDealFlag(DAX, "CLOSED", 0);
        runTo(500);
        assertTrue(fired.isEmpty(), "weekends self-suppress — no calendar, no hours config");
    }

    @Test
    void suspendFlagStandsTheWatchdogDown() {
        dog.onDealFlag(DAX, "SUSPEND", 0);
        runTo(500);
        assertTrue(fired.isEmpty(), "suspended markets are legitimately silent");
    }

    @Test
    void unknownFlagDoesNotSuppress() {
        dog.onDealFlag(DAX, "SOMETHINGNEW", 0);
        runTo(95);
        assertEquals(90, fired.get(0).second(),
                "a word IG has never sent keeps the watchdog watching (E1-T15, P9): judged like an open market"
                        + " until the word is added to DealFlag");
    }

    @Test
    void igsWeekendAndAuctionFlagsStandTheWatchdogDown() {
        // E1-T15: the 2026-10-16 close read CALL then EDIT, and the watchdog re-asked every 90s all weekend
        for (String flag : new String[] {"CALL", "EDIT", "AUCTION", "AUCTIONNOEDIT"}) {
            setUp();
            dog.onDealFlag(DAX, flag, 0);
            runTo(500);
            assertTrue(fired.isEmpty(), flag + " is not dealing: " + fired);
        }
    }

    @Test
    void theDealingFlagsAreWatched() {
        for (String flag : new String[] {"DEALNOEDIT", "CLOSINGSONLY"}) {
            setUp();
            dog.onDealFlag(DAX, flag, 0);
            runTo(95);
            assertEquals(90, fired.get(0).second(), flag + " streams prices: its silence is judged");
        }
    }

    @Test
    void healingTickResetsTheEpisodeIncludingItsResubscribeBudget() {
        runTo(90);                       // resub #1 fires
        dog.onTick(DAX, 91 * S);         // heals the TICK_SILENT episode
        runTo(180);                      // 89s of new silence — not stale yet
        assertEquals(1, fired.size());
        runTo(181);                      // fresh 90s window elapsed
        assertEquals(181, fired.get(1).second());
        assertEquals(Action.RESUBSCRIBE, fired.get(1).remedy().action(),
                "a fresh episode starts with a fresh resubscribe budget");
    }

    @Test
    void flowingTicksDoNotHealADeadChartEpisode() {
        for (long t = 10; t <= 600; t += 10) {
            dog.onTick(DAX, t * S);      // ticks flow the entire time
        }
        runTo(340);
        assertEquals(2, fired.size(), "ticks kept flowing yet the bar episode persisted");
        assertEquals(210, fired.get(0).second());
        assertEquals(330, fired.get(1).second(), "second remedy after the doubled 120s grace");
        assertEquals(Signal.BAR_SILENT_TICKS_FLOWING, fired.get(1).remedy().signal());
    }

    @Test
    void stormGuardDoublesGraceAndEscalatesToRebuild() {
        runTo(500);
        assertEquals(3, fired.size(), "storm guard: three remedies in 500s, never a storm");
        assertEquals(90, fired.get(0).second());
        assertEquals(Action.RESUBSCRIBE, fired.get(0).remedy().action());
        assertEquals(210, fired.get(1).second(), "grace doubled to 120s");
        assertEquals(Action.RESUBSCRIBE, fired.get(1).remedy().action());
        assertEquals(450, fired.get(2).second(), "grace doubled to 240s");
        assertEquals(Action.REBUILD, fired.get(2).remedy().action(),
                "resubscribes exhausted (max 2) — escalate");
    }

    @Test
    void twoMarketsStaleTogetherIsSessionShaped() {
        dog.track(NDX, 0);
        runTo(95);
        assertEquals(1, fired.size(), "one rebuild verdict, not per-market remedies");
        assertEquals(Action.REBUILD, fired.get(0).remedy().action());
    }

    @Test
    void hostSleepSkipsTheRoundAndRebaselines() {
        runTo(50);
        // the host suspended: one round later the wall clock has jumped 16 minutes, awake ~1s
        clockSecond++;
        assertEquals(List.of(),
                dog.evaluate(clockSecond * S, (clockSecond + 960) * 1000),
                "never fire INTO a recovery");
        // stopwatches rebased at wake: silence is measured from here, not from before
        long wake = clockSecond;
        while (clockSecond < wake + 89) {
            clockSecond++;
            assertEquals(List.of(),
                    dog.evaluate(clockSecond * S, (clockSecond + 960) * 1000));
        }
        clockSecond++;
        assertEquals(1, dog.evaluate(clockSecond * S, (clockSecond + 960) * 1000).size(),
                "a full fresh 90s window after wake does fire");
    }

    @Test
    void processFreezeSkipsTheRoundAndRebaselines() {
        runTo(50);
        clockSecond += 11;   // monotonic jumped 11s between rounds: the process froze
        assertEquals(List.of(), dog.evaluate(clockSecond * S, clockSecond * 1000));
        long resume = clockSecond;
        while (clockSecond < resume + 89) {
            clockSecond++;
            assertEquals(List.of(), dog.evaluate(clockSecond * S, clockSecond * 1000));
        }
        clockSecond++;
        assertEquals(1, dog.evaluate(clockSecond * S, clockSecond * 1000).size());
    }

    @Test
    void forgottenMarketNeverFires() {
        dog.forget(DAX);
        runTo(500);
        assertTrue(fired.isEmpty());
    }

    @Test
    void noVerdictWhileTheConnectionIsDownAndAFreshWindowOnResume() {
        // E1-T10 #5: silence on a down connection is the connection's, not a market's — the
        // escalator's domain; when streaming resumes every stopwatch starts afresh.
        dog.onConnection(false, 0);
        runTo(300);
        assertTrue(fired.isEmpty(), "stood down for the whole outage");
        dog.onConnection(true, 300 * S);
        runTo(389);
        assertTrue(fired.isEmpty(), "a fresh 90s window from the resume");
        runTo(390);
        assertEquals(List.of(new Fired(390, new Remedy(DAX, Action.RESUBSCRIBE, Signal.TICK_SILENT))), fired);
    }

    @Test
    void twoMarketsDyingWithinASweepEarnOneSessionVerdict() {
        // E1-T10 #33: the scar's feeds died 584ms apart. At 90 only DAX has crossed; NASDAQ is
        // within a sweep of crossing, so the round holds — at 91 they are stale together: one verdict.
        dog.track(NDX, 0);
        dog.onTick(NDX, 600_000_000L);
        runTo(90);
        assertTrue(fired.isEmpty(), "held: no market surgery for a session-shaped death");
        runTo(91);
        assertEquals(1, fired.size());
        assertEquals(91, fired.get(0).second());
        assertEquals(Action.REBUILD, fired.get(0).remedy().action());
    }

    @Test
    void aMarketClosedForTheWholePeriodIsTaughtOncePerPeriod() {
        // D29 (1): one teaching resubscribe per twelve hours of stand-down; still closed afterwards
        // (the snapshot re-taught the flag) means the next one is a period later, not a sweep later.
        dog.onDealFlag(DAX, "CLOSED", 0);
        runTo(43_199);
        assertTrue(fired.isEmpty());
        runTo(43_200);
        assertEquals(List.of(new Fired(43_200, new Remedy(DAX, Action.RESUBSCRIBE, Signal.STAND_DOWN_TEACHING))), fired);
        runTo(86_399);
        assertEquals(1, fired.size(), "once per period");
        runTo(86_400);
        assertEquals(2, fired.size());
    }

    @Test
    void aTaughtMarketThatStaysSilentClimbsTheOrdinaryLadderFromTheFreshSubscription() {
        // The zombie: nothing answers the teaching resubscribe, the shell forgets the old flag, and
        // the market is judged like any open, silent one — +90 resubscribe, +210 resubscribe, +450 rebuild.
        dog.onDealFlag(DAX, "CLOSED", 0);
        runTo(43_200);
        dog.clearDealFlag(DAX); // what the shell does as it re-asks for the pair
        runTo(43_650);
        assertEquals(List.of(43_200L, 43_290L, 43_410L, 43_650L),
                fired.stream().map(Fired::second).toList());
        assertEquals(Action.REBUILD, fired.get(3).remedy().action());
    }

    @Test
    void clearingTheFlagLiftsTheStandDown() {
        dog.onDealFlag(DAX, "CLOSED", 0);
        runTo(200);
        assertTrue(fired.isEmpty());
        dog.clearDealFlag(DAX);
        runTo(201);
        assertEquals(201, fired.get(0).second(), "the old subscription's flag no longer excuses the silence");
    }

    @Test
    void theHoldsBoundaryIsExactlyOneSweepBeforeTheNeighboursThreshold() {
        // NASDAQ's last tick one full second after DAX's: at 90 it is 89.0s silent — within one sweep
        // of its own 90 — so the round holds, and 91 renders the one session verdict.
        dog.track(NDX, 0);
        dog.onTick(NDX, 1 * S);
        runTo(90);
        assertTrue(fired.isEmpty(), "exactly one sweep short: held");
        runTo(91);
        assertEquals(List.of(91L), fired.stream().map(Fired::second).toList());
        assertEquals(Action.REBUILD, fired.get(0).remedy().action());
    }

    @Test
    void aNeighbourOneNanosecondOutsideTheSweepDoesNotHoldTheRound() {
        dog.track(NDX, 0);
        dog.onTick(NDX, 1 * S + 1);
        runTo(90);
        assertEquals(List.of(new Fired(90, new Remedy(DAX, Action.RESUBSCRIBE, Signal.TICK_SILENT))), fired,
                "not within a sweep: DAX gets its own remedy");
    }

    @Test
    void aSessionThatAnswersAndSendsNothingClimbsTheLadderToItsCap() {
        // E1-T12 ruling 4 (from the extended scar replay): every rebuilt connection says it streams
        // and nothing follows. The connection does not reset the session ladder — only data does —
        // so it doubles from 60s to the 10-minute session cap instead of re-firing every 90s.
        dog.track(NDX, 0);
        List<Long> verdicts = new ArrayList<>();
        while (clockSecond < 2200) {
            clockSecond++;
            for (Remedy r : dog.evaluate(clockSecond * S, clockSecond * 1000)) {
                assertEquals(Action.REBUILD, r.action());
                verdicts.add(clockSecond);
                dog.onConnection(false, clockSecond * S); // the rebuild tears the session down
                dog.onConnection(true, clockSecond * S + 1); // and the new one answers at once
            }
        }
        assertEquals(List.of(90L, 210L, 450L, 930L, 1530L, 2130L), verdicts,
                "grace 60s → 120 → 240 → 480 → 600 (the cap): a login every ten minutes at the limit");
    }

    @Test
    void aMarketReturningFromAJudgedSilenceIsNotJudgedBarSilentAsItReturns() {
        // E1-T12 finding 1 (the 2026-08-10 dawn replay): DAX silent long enough for the tick verdict,
        // NASDAQ alive throughout. When DAX's ticks return, no candle was owed for the silent stretch
        // — the CHART leg's clock restarts with the ticks, so the only remedy is the tick one.
        dog.track(NDX, 0);
        while (clockSecond < 400) {
            clockSecond++;
            dog.onTick(NDX, clockSecond * S);
            if (clockSecond <= 29 || clockSecond >= 230) { // back before the tick ladder's second remedy (239)
                dog.onTick(DAX, clockSecond * S);
            }
            if (clockSecond % 60 == 0) {
                dog.onSealedBar(NDX, clockSecond * S);
                if (clockSecond == 300 || clockSecond == 360) {
                    dog.onSealedBar(DAX, clockSecond * S); // the first candle after the return seals at 300
                }
            }
            for (Remedy r : dog.evaluate(clockSecond * S, clockSecond * 1000)) {
                fired.add(new Fired(clockSecond, r));
            }
        }
        assertEquals(List.of(new Fired(119, new Remedy(DAX, Action.RESUBSCRIBE, Signal.TICK_SILENT))), fired,
                "one tick remedy 90s after DAX's last tick (29s) — and nothing at 230, when DAX returns with no candle yet");
    }

    @Test
    void aMarketLeavingItsStandDownIsNotJudgedBarSilentAsItReopens() {
        // The T12 review's finding 1 (ruled 2026-10-10): DAX reads CLOSED from 10s and stands down
        // for an hour while NASDAQ trades; at 3610s it reopens with ticks flagged DEAL. No candle
        // was owed while closed — its CHART clock starts at the opening, not at boot.
        dog.track(NDX, 0);
        while (clockSecond < 3700) {
            clockSecond++;
            dog.onTick(NDX, clockSecond * S);
            if (clockSecond <= 10) {
                dog.onTick(DAX, clockSecond * S);
                dog.onDealFlag(DAX, "CLOSED", clockSecond * S);
            } else if (clockSecond >= 3610) {
                dog.onTick(DAX, clockSecond * S); // the Supervisor feeds the tick, then its flag
                dog.onDealFlag(DAX, "DEAL", clockSecond * S);
            }
            if (clockSecond % 60 == 0) {
                dog.onSealedBar(NDX, clockSecond * S);
                if (clockSecond >= 3660) {
                    dog.onSealedBar(DAX, clockSecond * S);
                }
            }
            for (Remedy r : dog.evaluate(clockSecond * S, clockSecond * 1000)) {
                fired.add(new Fired(clockSecond, r));
            }
        }
        assertEquals(List.of(), fired, "a daily break ends with no remedy — not a bar-silent re-ask as the data returns");
    }

    @Test
    void aLiveThirdMarketsTicksDoNotCloseTheSessionLadder() {
        // Three markets: DAX and NASDAQ dead, FTSE alive through every rebuild. Data never resets the
        // session ladder (E1-T13 — only time does), so FTSE's ticks say nothing about the dead two and
        // the ladder keeps climbing — not a rebuild every ~95s for as long as the two stay silent.
        String ftse = "IX.D.FTSE.DAILY.IP";
        dog.track(NDX, 0);
        dog.track(ftse, 0);
        List<Long> verdicts = new ArrayList<>();
        while (clockSecond < 1600) {
            clockSecond++;
            dog.onTick(ftse, clockSecond * S);
            if (clockSecond % 60 == 0) {
                dog.onSealedBar(ftse, clockSecond * S);
            }
            for (Remedy r : dog.evaluate(clockSecond * S, clockSecond * 1000)) {
                assertEquals(Action.REBUILD, r.action(), "a session verdict, never a remedy for FTSE");
                verdicts.add(clockSecond);
                dog.onConnection(false, clockSecond * S);
                dog.onConnection(true, clockSecond * S + 1);
            }
        }
        assertEquals(List.of(90L, 210L, 450L, 930L, 1530L), verdicts, "the ladder, as with two markets");
    }

    @Test
    void aThinButOpenPairConvergesToOneRebuildPerCap() {
        // E1-T13 (the re-review's finding 6, ruled 2026-10-11): both markets open and ticking together
        // every 200s, each tick-bearing minute sealing its candle — so tick-stale for the last 110s of
        // every gap, never bar-stale. Data does not reset the session ladder; its grace doubles per
        // verdict to the 10-minute cap, so the pair settles at one rebuild per cap. Derived from the
        // rule, not the run: a verdict when ≥2 are stale and grace has elapsed since the last; stale =
        // 90s after the later of the last tick and the last rebaseline (the rebuild).
        dog.track(NDX, 0);
        List<Long> verdicts = new ArrayList<>();
        while (clockSecond < 2400) {
            clockSecond++;
            if (clockSecond % 200 == 0) {
                dog.onTick(DAX, clockSecond * S);
                dog.onTick(NDX, clockSecond * S);
                dog.onSealedBar(DAX, clockSecond * S);
                dog.onSealedBar(NDX, clockSecond * S);
            }
            for (Remedy r : dog.evaluate(clockSecond * S, clockSecond * 1000)) {
                assertEquals(Action.REBUILD, r.action());
                verdicts.add(clockSecond);
                dog.onConnection(false, clockSecond * S);
                dog.onConnection(true, clockSecond * S + 1);
            }
        }
        assertEquals(List.of(90L, 290L, 530L, 1090L, 1690L, 2290L), verdicts,
                "grace 120 → 240 → 480 → 600 (cap) gates the verdicts: 200 apart, then 240, 560, then 600 for good");
    }

    @Test
    void aCapOfQuietAfterTheLadderForgetsIt() {
        // Two verdicts (grace now 240s), then the pair streams healthily for long enough that the grace
        // and a whole cap have passed since the last verdict: the ladder forgets, and the next outage is
        // judged at base — its second verdict 120s after its first, not 240.
        dog.track(NDX, 0);
        runTo(90);
        dog.onConnection(false, 90 * S);
        dog.onConnection(true, 90 * S + 1);
        runTo(210); // the second verdict, grace → 240
        dog.onConnection(false, 210 * S);
        dog.onConnection(true, 210 * S + 1);
        assertEquals(List.of(90L, 210L), fired.stream().map(Fired::second).toList());
        while (clockSecond < 1100) { // healthy until 1100: 890s since the verdict ≥ 240 + 600
            clockSecond++;
            dog.onTick(DAX, clockSecond * S);
            dog.onTick(NDX, clockSecond * S);
            if (clockSecond % 60 == 0) {
                dog.onSealedBar(DAX, clockSecond * S);
                dog.onSealedBar(NDX, clockSecond * S);
            }
            assertEquals(List.of(), dog.evaluate(clockSecond * S, clockSecond * 1000));
        }
        List<Long> next = new ArrayList<>();
        while (clockSecond < 1400) { // silent again from 1100
            clockSecond++;
            for (Remedy r : dog.evaluate(clockSecond * S, clockSecond * 1000)) {
                next.add(clockSecond);
                dog.onConnection(false, clockSecond * S);
                dog.onConnection(true, clockSecond * S + 1);
            }
        }
        assertEquals(List.of(1190L, 1310L), next, "a fresh outage: 90s after the last tick, then the base grace doubled — 120s");
    }

    @Test
    void theLadderForgetsExactlyWhenTheGraceAndACapHavePassed() {
        // The horizon is grace + cap after the verdict, inclusive: one verdict at 90 (grace → 120), then
        // the pair streams until 720 and both fall silent exactly 90 + 120 + 600 = 810 later than the
        // verdict's second. At the horizon the rung is forgotten — the outage is judged at base and its
        // second verdict follows 120 later, not the kept rung's 240.
        dog.track(NDX, 0);
        runTo(90);
        dog.onConnection(false, 90 * S);
        dog.onConnection(true, 90 * S + 1);
        assertEquals(List.of(90L), fired.stream().map(Fired::second).toList());
        while (clockSecond < 720) {
            clockSecond++;
            dog.onTick(DAX, clockSecond * S);
            dog.onTick(NDX, clockSecond * S);
            if (clockSecond % 60 == 0) {
                dog.onSealedBar(DAX, clockSecond * S);
                dog.onSealedBar(NDX, clockSecond * S);
            }
            assertEquals(List.of(), dog.evaluate(clockSecond * S, clockSecond * 1000));
        }
        List<Long> next = new ArrayList<>();
        while (clockSecond < 1100) {
            clockSecond++;
            for (Remedy r : dog.evaluate(clockSecond * S, clockSecond * 1000)) {
                next.add(clockSecond);
                dog.onConnection(false, clockSecond * S);
                dog.onConnection(true, clockSecond * S + 1);
            }
        }
        assertEquals(List.of(810L, 930L), next, "stale exactly at the horizon: judged at base, the next verdict 120 on");
    }

    @Test
    void aMarketsLadderSurvivesTheRebuildItEarned() {
        // E1-T13 (the re-review's finding 3): DAX dead from 0, NASDAQ alive. Resubscribe at 90 and 210,
        // REBUILD at 450 (grace now 480s). The rebuilt session streams; DAX is still dead. Its episode
        // must survive the rebuild it earned, so the next REBUILD waits the doubled grace — 930 — and the
        // one after that 960 more (1890), not a fresh ladder from base every ~450s.
        dog.track(NDX, 0);
        List<Fired> remedies = new ArrayList<>();
        while (clockSecond < 2000) {
            clockSecond++;
            dog.onTick(NDX, clockSecond * S);
            if (clockSecond % 60 == 0) {
                dog.onSealedBar(NDX, clockSecond * S);
            }
            for (Remedy r : dog.evaluate(clockSecond * S, clockSecond * 1000)) {
                remedies.add(new Fired(clockSecond, r));
                if (r.action() == Action.REBUILD) {
                    dog.onConnection(false, clockSecond * S);
                    dog.onConnection(true, clockSecond * S + 1);
                }
            }
        }
        assertEquals(List.of(
                new Fired(90, new Remedy(DAX, Action.RESUBSCRIBE, Signal.TICK_SILENT)),
                new Fired(210, new Remedy(DAX, Action.RESUBSCRIBE, Signal.TICK_SILENT)),
                new Fired(450, new Remedy(DAX, Action.REBUILD, Signal.TICK_SILENT)),
                new Fired(930, new Remedy(DAX, Action.REBUILD, Signal.TICK_SILENT)),
                new Fired(1890, new Remedy(DAX, Action.REBUILD, Signal.TICK_SILENT))), remedies,
                "the per-market ladder keeps doubling across the rebuilds it causes: 480, then 960");
    }
}
