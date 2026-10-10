package dev.amfshr.tradebench.marketdata.supervise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.marketdata.testutil.FakeClock;
import dev.amfshr.tradebench.marketdata.testutil.RecordingEventLog;

import dev.amfshr.tradebench.core.time.Clock;
import dev.amfshr.tradebench.ig.error.IgRetryableException;
import dev.amfshr.tradebench.ig.rest.ApplicationAllowance;
import dev.amfshr.tradebench.ig.session.IgSession;
import dev.amfshr.tradebench.ig.session.IgTokens;
import dev.amfshr.tradebench.marketdata.events.EventType;
import dev.amfshr.tradebench.marketdata.events.ServiceEvent;
import dev.amfshr.tradebench.marketdata.store.BestEffortEventLog;

class PacerDiscoveryTest {

    private static final IgSession SESSION = new IgSession(new IgTokens("cst", "xst"), "AB12CE",
            "https://demo-apd.marketdatasystems.com", List.of());
    private static final Instant NOW = Instant.parse("2026-09-28T07:00:05Z");
    private static final Clock FROZEN = new FakeClock(NOW);


    private final List<Integer> applied = new ArrayList<>();
    private final RecordingEventLog events = new RecordingEventLog();
    private final List<String> log = new ArrayList<>();

    private PacerDiscovery discovery(PacerDiscovery.AllowanceSource source) {
        return new PacerDiscovery(source, applied::add, new BestEffortEventLog(events, log::add),
                log::add, FROZEN);
    }

    private static ApplicationAllowance published(int accountOverall) {
        return allowance(accountOverall, 60);
    }

    private static ApplicationAllowance allowance(int accountOverall, int applicationOverall) {
        return new ApplicationAllowance("placeholder-key", accountOverall, applicationOverall, 100,
                10000, 40);
    }

    private ServiceEvent singleEvent(EventType type) {
        assertEquals(1, events.written.size(), "expected exactly one event");
        ServiceEvent event = events.written.get(0);
        assertEquals(type, event.type());
        return event;
    }

    @Test
    void appliesThePublishedBudgetMinusHeadroomAndRecordsIt() throws Exception {
        discovery(session -> published(30)).discover(SESSION);

        assertEquals(List.of(25), applied, "30 published, 5 kept back for the heal job");
        ServiceEvent event = singleEvent(EventType.PACER_DISCOVERED);
        assertEquals(30, event.detail().get("account").asInt());
        assertEquals(60, event.detail().get("application").asInt());
        assertEquals(30, event.detail().get("published").asInt(), "the binding figure");
        assertEquals(25, event.detail().get("used").asInt());
        assertEquals(5, event.detail().get("headroom").asInt());
        assertEquals(NOW, event.eventTimeUtc());
    }

    @Test
    void theTighterOfTheAccountAndOurKeyBinds() throws Exception {
        discovery(session -> allowance(30, 20)).discover(SESSION); // our key is the tighter one
        discovery(session -> allowance(20, 30)).discover(SESSION); // the account is (shared by every key)

        assertEquals(List.of(15, 15), applied, "min(account, application) − 5, whichever side is tighter");
        assertEquals(20, events.written.get(0).detail().get("published").asInt());
        assertEquals(20, events.written.get(1).detail().get("published").asInt());
    }

    @Test
    void aBudgetAtOrBelowTheHeadroomStillLeavesExactlyOne() throws Exception {
        discovery(session -> published(6)).discover(SESSION);
        discovery(session -> published(5)).discover(SESSION);
        discovery(session -> published(3)).discover(SESSION);

        assertEquals(List.of(1, 1, 1), applied,
                "6 - 5 = 1 exactly; 5 - 5 = 0 and 3 - 5 = -2 both clamp to 1, never 0 or negative");
    }

    @Test
    void anUnlistedKeyKeepsTheConservativeStartAndSaysSoInTheEventLog() throws Exception {
        discovery(session -> null).discover(SESSION);

        assertTrue(applied.isEmpty(), "never a stand-in from another key");
        ServiceEvent event = singleEvent(EventType.IG_API_ERROR);
        assertEquals("GET /operations/application", event.detail().get("operation").asText());
        assertTrue(event.detail().get("message").asText().contains("not listed"));
        assertTrue(log.getLast().contains("not listed"));
    }

    @Test
    void aFailedReadKeepsTheConservativeStartAndNeverThrows() throws Exception {
        discovery(session -> {
            throw new IOException("IG unreachable");
        }).discover(SESSION); // must not throw — discovery never stops boot

        assertTrue(applied.isEmpty());
        ServiceEvent event = singleEvent(EventType.IG_API_ERROR);
        assertTrue(event.detail().get("message").asText().contains("IG unreachable"));
        assertTrue(log.getLast().contains("failed"));
    }

    @Test
    void anIgErrorOnTheReadIsTheRealisticFailureAndIsHeldTheSameWay() throws Exception {
        discovery(session -> {
            throw new IgRetryableException("503 from IG", 503, null);
        }).discover(SESSION); // IgApiException is unchecked — the RuntimeException arm is load-bearing

        assertTrue(applied.isEmpty());
        singleEvent(EventType.IG_API_ERROR);
    }

    @Test
    void anUnwritableEventDoesNotUndoTheBudget() throws Exception {
        events.failWrites = true;

        discovery(session -> published(30)).discover(SESSION);

        assertEquals(List.of(25), applied, "the budget is applied before the breadcrumb is attempted");
        assertTrue(log.getLast().contains("event not written"));
    }
}
