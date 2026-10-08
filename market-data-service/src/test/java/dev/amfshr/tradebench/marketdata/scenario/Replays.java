package dev.amfshr.tradebench.marketdata.scenario;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Reads a captured replay (JSONL, one {@code {at, kind, payload}} per line — the format and the
 * exporters: {@code src/test/resources/replays/README.md}) into a {@link Scenario} builder, so
 * a test can set what the file does not say and run it like any policy scenario. Fails loud on
 * anything it does not understand: a replay is evidence, never approximately right. */
public final class Replays {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Replays() {
    }

    public static Scenario.Builder load(String resource) {
        try (InputStream in = Replays.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("no such replay on the classpath: " + resource);
            }
            return parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Scenario.Builder parse(List<String> lines) throws IOException {
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("an empty replay");
        }
        JsonNode header = MAPPER.readTree(lines.getFirst());
        require("replay".equals(header.path("kind").asText()), "the first line must be the replay header");
        JsonNode meta = header.path("payload");
        List<String> epics = new ArrayList<>();
        meta.path("epics").forEach(e -> epics.add(e.asText()));
        Instant origin = meta.hasNonNull("origin") ? Instant.parse(text(meta, "origin")) : null;
        Scenario.Builder scenario = Scenario.named(text(meta, "name"))
                .markets(epics.toArray(String[]::new))
                .until(Duration.ofMillis(number(meta, "untilMs")));
        if (meta.path("serverAnswers").asBoolean()) {
            scenario.serverAnswers();
        }
        if (origin != null) {
            scenario.origin(origin);
        }
        for (int i = 1; i < lines.size(); i++) {
            JsonNode line = MAPPER.readTree(lines.get(i));
            long at = number(line, "at");
            scenario.at(Duration.ofMillis(at), event(text(line, "kind"), line.path("payload"),
                    origin == null ? null : origin.plusMillis(at)));
        }
        return scenario;
    }

    private static Event event(String kind, JsonNode p, @Nullable Instant at) {
        return switch (kind) {
            case "status" -> new Event.Status(text(p, "status"));
            case "serverError" -> new Event.ServerError((int) number(p, "code"), text(p, "message"));
            case "confirm" -> new Event.Confirm(text(p, "epic"), Event.Leg.valueOf(text(p, "leg")));
            case "reject" -> new Event.Reject(text(p, "epic"), Event.Leg.valueOf(text(p, "leg")),
                    (int) number(p, "code"), text(p, "message"));
            case "tick" -> new Event.Tick(text(p, "epic"), text(p, "bid"), text(p, "ask"),
                    p.has("dealFlag") ? text(p, "dealFlag") : "DEAL", at);
            case "bar" -> new Event.Bar(text(p, "epic"), quote(p.path("bid")), quote(p.path("ask")),
                    p.has("startUtc") ? Instant.parse(text(p, "startUtc")) : null,
                    p.hasNonNull("ltv") ? number(p, "ltv") : null);
            case "dbDown" -> new Event.DbDown();
            case "dbUp" -> new Event.DbUp();
            case "dbBroken" -> new Event.DbBroken();
            case "dbWritesRefused" -> new Event.DbWritesRefused();
            case "hostSleep" -> new Event.HostSleep(Duration.ofMillis(number(p, "ms")));
            case "igDown" -> new Event.IgDown();
            case "igUp" -> new Event.IgUp();
            case "stop" -> new Event.Stop();
            default -> throw new IllegalArgumentException("unknown replay event kind: " + kind);
        };
    }

    private static Event.Quote quote(JsonNode q) {
        return new Event.Quote(text(q, "open"), text(q, "high"), text(q, "low"), text(q, "close"));
    }

    private static long number(JsonNode node, String field) {
        require(node.hasNonNull(field) && node.get(field).isNumber(),
                "a replay line needs a number for '" + field + "': " + node);
        return node.get(field).asLong();
    }

    private static String text(JsonNode node, String field) {
        require(node.hasNonNull(field), "a replay line is missing '" + field + "': " + node);
        return node.get(field).asText();
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }
}
