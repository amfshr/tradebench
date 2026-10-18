package dev.amfshr.tradebench.marketdata.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.amfshr.tradebench.core.domain.Bar1m;
import dev.amfshr.tradebench.core.domain.OhlcPrices;
import dev.amfshr.tradebench.core.domain.Tick;
import dev.amfshr.tradebench.marketdata.events.EventType;
import dev.amfshr.tradebench.marketdata.events.ServiceEvent;
import dev.amfshr.tradebench.marketdata.store.PostgresObservabilityStore;
import dev.amfshr.tradebench.marketdata.store.PostgresStore;
import dev.amfshr.tradebench.marketdata.testutil.PostgresTestBase;

/**
 * The Tradebench exporter end to end (E1-T12 ruling 3): rows written by the real stores — ticks, a
 * candle, and the raw status transitions the belt now records — come back out as a replay the
 * harness loads, with the real connection sequence and no healthy-server answer modelled. The
 * psql dressing ({@code \set}, {@code :'var'}) is rendered here as the wrapper script would.
 */
class ExportTradebenchSqlTest extends PostgresTestBase {

    private static final String DAX = "IX.D.DAX.DAILY.IP";
    private static final Instant ANCHOR = Instant.parse("2026-10-07T15:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void emptyDatabase() throws SQLException {
        try (Connection c = database.dataSource().getConnection(); Statement s = c.createStatement()) {
            s.execute("TRUNCATE ticks, bars_1m, service_events, bar_gaps, capture_status, instruments"
                    + " RESTART IDENTITY CASCADE");
        }
    }

    @Test
    void aWindowWithRecordedStatusesReplaysItsRealSequenceAndModelsNoAnswer() throws Exception {
        PostgresStore sink = new PostgresStore(database.dataSource(), "platform", "ig-stream-demo");
        sink.write(new Tick(DAX, ANCHOR.minusSeconds(30), new BigDecimal("24510.5"), new BigDecimal("24511.7")));
        sink.write(new Tick(DAX, ANCHOR.plusSeconds(5), new BigDecimal("24512.0"), new BigDecimal("24513.2")));
        sink.write(new Bar1m(DAX, ANCHOR, // the 15:00 candle, sealed at 15:01 — inside a 130s window from 14:59
                new OhlcPrices(new BigDecimal("1"), new BigDecimal("2"), new BigDecimal("0.5"), new BigDecimal("1.5")),
                new OhlcPrices(new BigDecimal("1.1"), new BigDecimal("2.1"), new BigDecimal("0.6"), new BigDecimal("1.6")),
                null));
        sink.close();
        PostgresObservabilityStore events = new PostgresObservabilityStore(database.dataSource(),
                "platform", "ig-stream-demo", "test-run");
        events.write(statusAt(ANCHOR.minusSeconds(60), "CONNECTED:WS-STREAMING"));
        events.write(statusAt(ANCHOR.plusSeconds(2), "DISCONNECTED:WILL-RETRY"));
        events.write(statusAt(ANCHOR.plusSeconds(90), "CONNECTED:WS-STREAMING")); // past the window

        Scenario replay = Replays.parse(export(Map.of(
                "anchor", "2026-10-07 15:00:00+00", "before_secs", "60", "until_secs", "130",
                "name", "a captured window", "recorded", "two ticks, a candle, two transitions",
                "modelled", "nothing", "user_name", "platform", "source_name", "ig-stream-demo"))).build();

        assertFalse(replay.serverAnswers, "the window holds the real sequence — the harness models no answer");
        assertEquals(List.of(new Event.Status("CONNECTED:WS-STREAMING"), new Event.Status("DISCONNECTED:WILL-RETRY")),
                replay.steps.stream().map(Scenario.Step::event).filter(e -> e instanceof Event.Status).toList(),
                "the transitions inside the window, in order; the one past it is not replayed");
        assertEquals(List.of(0L, 62_000L), replay.steps.stream().filter(s -> s.event() instanceof Event.Status)
                .map(s -> s.at().toMillis()).toList(), "offsets from the origin (anchor − 60s)");
        assertEquals(2, replay.steps.stream().filter(s -> s.event() instanceof Event.Tick).count());
        assertEquals(1, replay.steps.stream().filter(s -> s.event() instanceof Event.Bar).count(),
                "the 15:00 candle — the window's first, partial minute (14:59) is never a candle");
        assertEquals(Duration.ofSeconds(130), replay.until);
    }

    @Test
    void aWindowWithoutRecordedStatusesIsAnsweredByTheHarnessAndSaysSo() throws Exception {
        PostgresStore sink = new PostgresStore(database.dataSource(), "platform", "ig-stream-demo");
        sink.write(new Tick(DAX, ANCHOR, new BigDecimal("24510.5"), new BigDecimal("24511.7")));
        sink.close();

        List<String> lines = export(Map.of(
                "anchor", "2026-10-07 15:00:00+00", "before_secs", "10", "until_secs", "20",
                "name", "before E1-T12", "recorded", "one tick", "modelled", "the server's answers",
                "user_name", "platform", "source_name", "ig-stream-demo"));

        assertTrue(Replays.parse(lines).build().serverAnswers);
        assertTrue(MAPPER.readTree(lines.getFirst()).path("payload").path("source").asText()
                .contains("no connection_status events in the window"), "the header says which");
    }

    @Test
    void eachTickCarriesTheDealFlagInForceAtItsInstant() throws Exception {
        // the shape of the 2026-10-16 close: the flag changes ride on ticks stamped with the same instant
        Instant close = ANCHOR;
        PostgresStore sink = new PostgresStore(database.dataSource(), "platform", "ig-stream-demo");
        sink.write(new Tick(DAX, close.minusSeconds(20), new BigDecimal("24510.5"), new BigDecimal("24511.7"))); // before any flag
        sink.write(new Tick(DAX, close.minusSeconds(1), new BigDecimal("24510.6"), new BigDecimal("24511.8")));
        sink.write(new Tick(DAX, close.plusMillis(39), new BigDecimal("24510.7"), new BigDecimal("24511.9")));
        sink.write(new Tick(DAX, close.plusMillis(82), new BigDecimal("24510.7"), new BigDecimal("24511.9")));
        sink.write(new Tick(DAX, close.plusSeconds(60), new BigDecimal("24510.7"), new BigDecimal("24511.9")));
        sink.close();
        PostgresObservabilityStore events = new PostgresObservabilityStore(database.dataSource(),
                "platform", "ig-stream-demo", "test-run");
        events.write(flagAt(close.minusSeconds(10), "DEAL"));
        events.write(flagAt(close.plusMillis(39), "CALL"));
        events.write(flagAt(close.plusMillis(82), "EDIT"));

        Scenario replay = Replays.parse(export(Map.of(
                "anchor", "2026-10-07 15:00:00+00", "before_secs", "30", "until_secs", "120",
                "name", "a close", "recorded", "five ticks, three flags", "modelled", "the server's answers",
                "user_name", "platform", "source_name", "ig-stream-demo"))).build();

        assertEquals(List.of("DEAL", "DEAL", "CALL", "EDIT", "EDIT"), replay.steps.stream()
                .map(Scenario.Step::event).filter(e -> e instanceof Event.Tick).map(e -> ((Event.Tick) e).dealFlag())
                .toList(), "the first tick predates every flag (the harness's DEAL); a flag stamped at a tick's"
                        + " instant is that tick's; later ticks keep the last one");
    }

    private static ServiceEvent flagAt(Instant at, String flag) {
        return ServiceEvent.of(EventType.MARKET_STATE_CHANGE, at).forEpic(DAX)
                .withDetail(MAPPER.createObjectNode().put("dealFlag", flag));
    }

    private static ServiceEvent statusAt(Instant at, String status) {
        return ServiceEvent.of(EventType.CONNECTION_STATUS, at)
                .withDetail(MAPPER.createObjectNode().put("status", status));
    }

    /** Runs export-tradebench.sql as psql would: {@code \set} lines dropped, {@code :'var'} as a
     * quoted literal, {@code :var} bare — each value a trusted test constant. */
    private static List<String> export(Map<String, String> vars) throws SQLException, IOException {
        String sql;
        try (InputStream in = ExportTradebenchSqlTest.class.getResourceAsStream("/replays/export-tradebench.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        sql = sql.lines().filter(l -> !l.startsWith("\\set") && !l.startsWith("--")).reduce("", (a, b) -> a + "\n" + b);
        for (Map.Entry<String, String> v : vars.entrySet()) {
            sql = sql.replace(":'" + v.getKey() + "'", "'" + v.getValue().replace("'", "''") + "'")
                    .replace(":" + v.getKey(), v.getValue());
        }
        List<String> lines = new ArrayList<>();
        try (Connection c = database.dataSource().getConnection(); Statement s = c.createStatement();
                ResultSet r = s.executeQuery(sql)) {
            while (r.next()) {
                lines.add(r.getString(1));
            }
        }
        return lines;
    }
}
