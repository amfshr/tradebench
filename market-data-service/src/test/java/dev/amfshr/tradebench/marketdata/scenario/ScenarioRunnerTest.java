package dev.amfshr.tradebench.marketdata.scenario;

import static dev.amfshr.tradebench.marketdata.scenario.Events.streaming;
import static dev.amfshr.tradebench.marketdata.scenario.Events.subscribed;
import static dev.amfshr.tradebench.marketdata.scenario.Events.tick;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.marketdata.events.EventType;

/** The instrument's own promises: its failures are never the system's. */
class ScenarioRunnerTest {

    private static final String DAX = "IX.D.DAX.DAILY.IP";

    @Test
    void aFixtureThatCannotBeAppliedIsAHarnessErrorNotAPumpDeath() {
        Scenario broken = Scenario.named("a tick for a market nobody watches").markets(DAX)
                .at(Duration.ZERO, streaming()).at(Duration.ZERO, subscribed(DAX))
                .at(Duration.ofSeconds(1), tick("IX.D.NASDAQ.CASH.IP"))
                .until(Duration.ofSeconds(5)).build();

        ScenarioRunner.HarnessError error = assertThrows(ScenarioRunner.HarnessError.class,
                () -> ScenarioRunner.run(broken));

        assertInstanceOf(IllegalArgumentException.class, error.getCause(), "the fake wire's refusal, kept");
        assertTrue(error.getMessage().contains("PT1S"), error.getMessage());
    }

    @Test
    void aLongStandDownRunsToItsEnd() throws Exception {
        // Eight hours of a closed, silent market is 115 000 idle waits — more than the sleeper's
        // default guard; the runner sizes the guard from the scenario.
        Scenario night = Scenario.named("a closed market overnight").markets(DAX)
                .at(Duration.ZERO, streaming()).at(Duration.ZERO, subscribed(DAX))
                .at(Duration.ofSeconds(1), tick(DAX, "CLOSED"))
                .until(Duration.ofHours(8)).build();

        Observed o = ScenarioRunner.run(night);

        assertEquals(480, o.heartbeats.size(), "one heartbeat a minute, to the end");
        assertTrue(o.exits.isEmpty());
    }

    @Test
    void aReplaysTicksKeepTheirOwnInstants() throws Exception {
        Instant origin = Instant.parse("2026-08-04T12:32:08.916Z");
        Scenario replay = Scenario.named("dated").markets(DAX).origin(origin).serverAnswers()
                .at(Duration.ofMillis(25), new Event.Tick(DAX, "1", "2", "DEAL", origin.plusMillis(25)))
                .until(Duration.ofSeconds(2)).build();

        Observed o = ScenarioRunner.run(replay);

        assertEquals(List.of("tick@" + origin.plusMillis(25).toEpochMilli()), o.landed, "the fixture's millisecond, not the clock's slice");
        assertEquals(Duration.ofMillis(25), o.events(EventType.MARKET_STATE_CHANGE).get(0).at(),
                "and the event it raised is dated from the fixture's origin (the boot's status row is dated 0)");
    }
}
