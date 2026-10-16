package dev.amfshr.tradebench.marketdata.heal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** E1-T6 ③: a minute's state from IG's flags and the collector's observation, at the boundaries. */
class DaySpansTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 15);
    private static final List<Window> ALL_DAY = List.of(new Window(at("00:00:00"), at("23:59:59.999")));

    @Test
    void aFlagChangeExactlyOnAMinuteBoundaryGovernsThatMinute() {
        Map<Instant, MinuteState> m = DaySpans.classify(D, "DEAL", List.of(new FlagChange(at("21:00:00"), "CLOSED")),
                ALL_DAY);
        assertEquals(MinuteState.OPEN, m.get(at("20:59:00")));
        assertEquals(MinuteState.CLOSED, m.get(at("21:00:00")), "closed from the instant it changed");
    }

    @Test
    void aMinuteIsOpenIfDealWasInForceAtAnyPointInIt() {
        Map<Instant, MinuteState> opening = DaySpans.classify(D, "CLOSED",
                List.of(new FlagChange(at("06:30:20"), "DEAL")), ALL_DAY);
        assertEquals(MinuteState.CLOSED, opening.get(at("06:29:00")));
        assertEquals(MinuteState.OPEN, opening.get(at("06:30:00")), "opened twenty seconds in");

        Map<Instant, MinuteState> closing = DaySpans.classify(D, "DEAL",
                List.of(new FlagChange(at("21:00:30"), "CLOSED")), ALL_DAY);
        assertEquals(MinuteState.OPEN, closing.get(at("21:00:00")), "still open for its first thirty seconds");
        assertEquals(MinuteState.CLOSED, closing.get(at("21:01:00")));
    }

    @Test
    void aMinuteTheCollectorDidNotWholyObserveIsUnknownWhateverTheFlagSaid() {
        List<Window> downFrom1230To1300 = List.of(new Window(at("00:00:00"), at("12:30:15")),
                new Window(at("13:00:00"), at("23:59:59.999")));
        Map<Instant, MinuteState> m = DaySpans.classify(D, "DEAL", List.of(), downFrom1230To1300);
        assertEquals(MinuteState.OPEN, m.get(at("12:29:00")));
        assertEquals(MinuteState.UNKNOWN, m.get(at("12:30:00")), "the last sign of life fell mid-minute");
        assertEquals(MinuteState.UNKNOWN, m.get(at("12:59:00")));
        assertEquals(MinuteState.OPEN, m.get(at("13:00:00")), "observed again from the boot");
    }

    @Test
    void anObservedMinuteBeforeIgHasSaidAnythingIsUnknown() {
        Map<Instant, MinuteState> m = DaySpans.classify(D, null, List.of(new FlagChange(at("08:00:10"), "DEAL")),
                ALL_DAY);
        assertEquals(MinuteState.UNKNOWN, m.get(at("07:59:00")), "no word from IG yet");
        assertEquals(MinuteState.OPEN, m.get(at("08:00:00")));
        assertEquals(1440, m.size(), "every minute of the day");
    }

    private static Instant at(String time) {
        return Instant.parse(D + "T" + time + "Z");
    }
}
