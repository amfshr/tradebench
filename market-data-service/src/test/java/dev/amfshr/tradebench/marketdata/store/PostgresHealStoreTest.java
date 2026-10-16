package dev.amfshr.tradebench.marketdata.store;

import dev.amfshr.tradebench.marketdata.testutil.PostgresTestBase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.amfshr.tradebench.core.domain.Bar1m;
import dev.amfshr.tradebench.core.domain.OhlcPrices;
import dev.amfshr.tradebench.core.domain.Tick;
import dev.amfshr.tradebench.marketdata.coverage.GapDetector;
import dev.amfshr.tradebench.marketdata.events.EventType;
import dev.amfshr.tradebench.marketdata.events.ServiceEvent;
import dev.amfshr.tradebench.marketdata.heal.FlagChange;
import dev.amfshr.tradebench.marketdata.heal.OpenGap;
import dev.amfshr.tradebench.marketdata.heal.Run;
import dev.amfshr.tradebench.marketdata.heal.Window;

/** E1-T6 ③: the heal's view of the record, read back from rows the stream really writes. */
class PostgresHealStoreTest extends PostgresTestBase {

    private static final String DAX = "IX.D.DAX.DAILY.IP";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private PostgresStore stream;
    private PostgresObservabilityStore events;
    private PostgresHealStore heal;

    @BeforeEach
    void clean() throws Exception {
        try (Connection c = database.dedicatedConnection(); Statement s = c.createStatement()) {
            s.execute("TRUNCATE ticks, bars_1m, service_events, bar_gaps RESTART IDENTITY");
        }
        stream = new PostgresStore(database.dataSource(), "platform", "ig-stream-demo");
        events = new PostgresObservabilityStore(database.dataSource(), "platform", "ig-stream-demo", "test-run");
        heal = new PostgresHealStore(database.dataSource(), "platform", "ig-stream-demo", "ig-rest-demo", "test-run");
    }

    @AfterEach
    void closeTheStream() {
        stream.close(); // it holds a pooled connection for its life
    }

    @Test
    void aHealedBarIsWrittenUnderTheRestSourceAndCountsAsPresent() throws Exception {
        stream.write(bar("2026-10-15T10:00:00Z"));
        heal.writeHealed(List.of(bar("2026-10-15T10:01:00Z")));

        assertEquals(Set.of(at("10:00:00"), at("10:01:00")),
                heal.barMinutes(DAX, at("00:00:00"), at("23:59:00")), "a minute is present from either source");
        try (Connection c = database.dedicatedConnection(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT so.name FROM bars_1m b JOIN sources so ON so.id = b.source_id"
                     + " ORDER BY b.start_utc")) {
            r.next();
            assertEquals("ig-stream-demo", r.getString(1));
            r.next();
            assertEquals("ig-rest-demo", r.getString(1), "a healed minute is always told from a streamed one (P8)");
        }
    }

    @Test
    void healedMinutesAreTheRestSourcesOnly() {
        stream.write(bar("2026-10-15T10:00:00Z"));
        heal.writeHealed(List.of(bar("2026-10-15T10:01:00Z")));

        assertEquals(Set.of(at("10:01:00")), heal.healedMinutes(DAX, at("00:00:00"), Instant.parse("2026-10-16T00:00:00Z")),
                "a streamed minute is present but was never healed");
    }

    @Test
    void theFlagsAreReadBackInOrderAndTheOneInForceBeforeADay() {
        flag("2026-10-14T21:00:00Z", "CLOSED");
        flag("2026-10-15T06:00:00Z", "DEAL");
        flag("2026-10-15T21:00:00Z", "CLOSED");

        assertEquals("CLOSED", heal.flagBefore(DAX, at("00:00:00")), "the last change before midnight");
        assertEquals(List.of(new FlagChange(at("06:00:00"), "DEAL"), new FlagChange(at("21:00:00"), "CLOSED")),
                heal.flagChanges(DAX, at("00:00:00"), Instant.parse("2026-10-16T00:00:00Z")));
        assertNull(heal.flagBefore(DAX, Instant.parse("2026-10-14T21:00:00Z")), "nothing before the first");
    }

    @Test
    void observationRunsFromEachBootsFirstStreamingStatusToTheLastSignOfLifeBeforeTheNext() throws Exception {
        lifecycle(EventType.SERVICE_START, "2026-10-15T10:00:00Z");
        status("2026-10-15T10:00:04Z", "CONNECTED:STREAM-SENSING");
        status("2026-10-15T10:00:05Z", "CONNECTED:WS-STREAMING");
        stream.write(new Tick(DAX, Instant.parse("2026-10-15T11:00:30Z"), BigDecimal.ONE, BigDecimal.TWO));
        stream.flush();
        lifecycle(EventType.SERVICE_START, "2026-10-15T12:00:00Z");
        status("2026-10-15T12:00:06Z", "CONNECTED:WS-STREAMING");

        List<Window> windows = heal.observed(DAX, at("00:00:00"), Instant.parse("2026-10-16T00:00:00Z"));

        assertEquals(new Window(at("10:00:05"), at("11:00:30")), windows.get(0),
                "from the first streaming status — the handshake is not observing — to the last tick before the reboot");
        assertEquals(at("12:00:06"), windows.get(1).from());
        assertEquals(2, windows.size());
    }

    @Test
    void aStreamOutageInsideOneRunIsNotObserved() {
        // the doctrine review's finding 1: the process stays up while the stream is down, so a
        // reopen during the outage must read as unknown, not as the CLOSED flag still in force
        lifecycle(EventType.SERVICE_START, "2026-10-15T08:00:00Z");
        status("2026-10-15T08:00:05Z", "CONNECTED:WS-STREAMING");
        status("2026-10-15T21:10:00Z", "DISCONNECTED:WILL-RETRY");
        status("2026-10-15T21:10:02Z", "CONNECTING");
        status("2026-10-15T22:30:00Z", "CONNECTED:WS-STREAMING");

        List<Window> windows = heal.observed(DAX, at("00:00:00"), Instant.parse("2026-10-16T00:00:00Z"));

        assertEquals(new Window(at("08:00:05"), at("21:10:00")), windows.get(0));
        assertEquals(at("22:30:00"), windows.get(1).from(), "observed again only once it streamed");
    }

    @Test
    void historyBeforeAnyBootWasRecordedIsObservedToItsLastSignOfLife() throws Exception {
        // pre-E1-T6 history: no boot rows existed yet — taking it as unobserved would ask IG for every
        // minute of it and spend the week's allowance on days the stream did capture
        stream.write(new Tick(DAX, Instant.parse("2026-10-15T09:30:00Z"), BigDecimal.ONE, BigDecimal.TWO));
        stream.flush();
        lifecycle(EventType.SERVICE_START, "2026-10-15T12:00:00Z");

        List<Window> windows = heal.observed(DAX, at("00:00:00"), Instant.parse("2026-10-16T00:00:00Z"));

        assertEquals(new Window(Instant.EPOCH, at("09:30:00")), windows.getFirst());
    }

    @Test
    void aRecordWithNoBootsAtAllIsObservedThroughout() {
        List<Window> windows = heal.observed(DAX, at("00:00:00"), Instant.parse("2026-10-16T00:00:00Z"));

        assertEquals(1, windows.size());
        assertEquals(Instant.EPOCH, windows.getFirst().from());
        assertTrue(windows.getFirst().to().isAfter(Instant.parse("2026-10-16T00:00:00Z")));
    }

    @Test
    void theFirstRecordIsTheEarliestStreamedTickOrBar() throws Exception {
        assertNull(heal.firstRecord(DAX), "nothing yet");
        stream.write(bar("2026-10-15T09:00:00Z"));
        stream.write(new Tick(DAX, Instant.parse("2026-10-15T08:59:10Z"), BigDecimal.ONE, BigDecimal.TWO));
        stream.flush();

        assertEquals(Instant.parse("2026-10-15T08:59:10Z"), heal.firstRecord(DAX));
    }

    @Test
    void anOpenGapIsClosedWithItsOutcomeAndAHealFoundGapIsRecordedOnce() throws Exception {
        events.record(new GapDetector.Gap(DAX, at("10:00:00"), at("10:02:00"), 3));
        List<OpenGap> open = heal.openGapsEnding(DAX, at("00:00:00"), Instant.parse("2026-10-16T00:00:00Z"));
        assertEquals(1, open.size());

        heal.closeGap(open.getFirst().id(), at("23:59:00"), "healed");
        heal.recordHealedGap(DAX, new Run(at("12:30:00"), at("12:59:00")), at("23:59:00"), "tickless_at_source");
        heal.recordHealedGap(DAX, new Run(at("12:30:00"), at("12:59:00")), at("23:59:30"), "healed"); // a re-run

        assertTrue(heal.openGapsEnding(DAX, at("00:00:00"), Instant.parse("2026-10-16T00:00:00Z")).isEmpty());
        try (Connection c = database.dedicatedConnection(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT heal_outcome, missing_minutes FROM bar_gaps ORDER BY gap_from_utc")) {
            r.next();
            assertEquals("healed", r.getString(1));
            r.next();
            assertEquals("healed", r.getString(1), "a re-run updates the row in place");
            assertEquals(30, r.getInt(2));
            assertTrue(!r.next(), "never a second row for the same span");
        }
    }

    private void flag(String iso, String dealFlag) {
        events.write(ServiceEvent.of(EventType.MARKET_STATE_CHANGE, Instant.parse(iso)).forEpic(DAX)
                .withDetail(MAPPER.createObjectNode().put("dealFlag", dealFlag)));
    }

    private void status(String iso, String status) {
        events.write(ServiceEvent.of(EventType.CONNECTION_STATUS, Instant.parse(iso))
                .withDetail(MAPPER.createObjectNode().put("status", status)));
    }

    private void lifecycle(EventType type, String iso) {
        events.write(ServiceEvent.of(type, Instant.parse(iso)).withDetail(MAPPER.createObjectNode().put("instance", "test-run")));
    }

    private static Instant at(String time) {
        return Instant.parse("2026-10-15T" + time + "Z");
    }

    private static Bar1m bar(String iso) {
        OhlcPrices p = new OhlcPrices(BigDecimal.ONE, BigDecimal.TWO, BigDecimal.ONE, BigDecimal.TWO);
        return new Bar1m(DAX, Instant.parse(iso), p, p, null);
    }
}
