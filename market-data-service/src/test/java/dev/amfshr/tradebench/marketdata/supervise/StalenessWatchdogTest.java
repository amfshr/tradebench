package dev.amfshr.tradebench.marketdata.supervise;

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
        dog = new StalenessWatchdog(Tuning.playbook());
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
    void closedAndSuspendFlagsStandTheWatchdogDown() {
        dog.onDealFlag(DAX, "CLOSED");
        runTo(500);
        assertTrue(fired.isEmpty(), "weekends self-suppress — no calendar, no hours config");
    }

    @Test
    void unknownFlagDoesNotSuppress() {
        dog.onDealFlag(DAX, "AUCTION");
        runTo(95);
        assertEquals(90, fired.get(0).second(),
                "a resubscribe on a closed market is harmless and its snapshot teaches");
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
        // lid closed: one round later the wall clock has jumped 16 minutes, awake ~1s
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
}
