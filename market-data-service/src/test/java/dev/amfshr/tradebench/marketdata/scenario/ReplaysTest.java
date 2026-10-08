package dev.amfshr.tradebench.marketdata.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The loader fails loud: a replay is evidence, and an approximately-read one is worse than none. */
class ReplaysTest {

    private static final String HEADER = "{\"at\":0,\"kind\":\"replay\",\"payload\":{\"name\":\"t\","
            + "\"epics\":[\"IX.D.DAX.DAILY.IP\"],\"serverAnswers\":true,\"untilMs\":5000}}";

    @Test
    void readsTheHeaderAndEveryKindOfLine() throws Exception {
        Scenario s = Replays.parse(List.of(HEADER,
                "{\"at\":25,\"kind\":\"tick\",\"payload\":{\"epic\":\"IX.D.DAX.DAILY.IP\",\"bid\":\"1.5\",\"ask\":\"2.5\"}}",
                "{\"at\":60000,\"kind\":\"bar\",\"payload\":{\"epic\":\"IX.D.DAX.DAILY.IP\",\"startUtc\":\"2026-08-04T12:33:00Z\","
                        + "\"bid\":{\"open\":\"1\",\"high\":\"2\",\"low\":\"0\",\"close\":\"1\"},"
                        + "\"ask\":{\"open\":\"1\",\"high\":\"2\",\"low\":\"0\",\"close\":\"1\"}}}",
                "{\"at\":70000,\"kind\":\"hostSleep\",\"payload\":{\"ms\":960000}}",
                "{\"at\":80000,\"kind\":\"stop\",\"payload\":{}}")).build();

        assertEquals(List.of("IX.D.DAX.DAILY.IP"), s.epics);
        assertTrue(s.serverAnswers);
        assertEquals(Duration.ofSeconds(5), s.until);
        assertEquals(new Event.Tick("IX.D.DAX.DAILY.IP", "1.5", "2.5", "DEAL"), s.steps.get(0).event(),
                "a tick without a deal flag is assumed to be trading");
        assertEquals(Duration.ofMillis(25), s.steps.get(0).at());
        assertEquals(Instant.parse("2026-08-04T12:33:00Z"), ((Event.Bar) s.steps.get(1).event()).startUtc());
        assertEquals(new Event.HostSleep(Duration.ofMinutes(16)), s.steps.get(2).event());
        assertEquals(new Event.Stop(), s.steps.get(3).event());
    }

    @Test
    void refusesAnUnknownKind() {
        assertThrows(IllegalArgumentException.class, () -> Replays.parse(List.of(HEADER,
                "{\"at\":1,\"kind\":\"silence\",\"payload\":{}}")));
    }

    @Test
    void refusesALineMissingAField() {
        assertThrows(IllegalArgumentException.class, () -> Replays.parse(List.of(HEADER,
                "{\"at\":1,\"kind\":\"tick\",\"payload\":{\"epic\":\"IX.D.DAX.DAILY.IP\",\"bid\":\"1.5\"}}")));
    }

    @Test
    void refusesAFileThatDoesNotStartWithTheHeader() {
        assertThrows(IllegalArgumentException.class, () -> Replays.parse(List.of(
                "{\"at\":1,\"kind\":\"tick\",\"payload\":{\"epic\":\"IX.D.DAX.DAILY.IP\",\"bid\":\"1.5\",\"ask\":\"2\"}}")));
    }
}
