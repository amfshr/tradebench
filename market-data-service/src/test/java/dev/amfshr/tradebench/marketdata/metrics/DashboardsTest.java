package dev.amfshr.tradebench.marketdata.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The dashboards in deploy/grafana/ against the metric contract (E1-T16): every {@code tradebench_*} series
 * a panel queries is one the collector emits, under the name Grafana Cloud stores it by — OpenTelemetry's
 * Prometheus translation: dots to underscores, a unit suffix ({@code s} → {@code _seconds}, {@code min} →
 * {@code _minutes} unless the name already says it, braced units none), {@code _total} on a counter,
 * {@code _bucket}/{@code _sum}/{@code _count} on a histogram. The table below is that translation done by
 * hand from the nod's metric set — not derived from the code, so a renamed metric fails here first.
 */
class DashboardsTest {

    private static final Path DIR = Path.of("../deploy/grafana");
    private static final Map<String, String> SERIES = Map.ofEntries(
            Map.entry("tradebench_capture_ticks_total", CollectorMetrics.TICKS),
            Map.entry("tradebench_capture_bars_total", CollectorMetrics.BARS),
            Map.entry("tradebench_capture_ticks_dropped_total", CollectorMetrics.TICKS_DROPPED),
            Map.entry("tradebench_capture_updates_malformed_total", CollectorMetrics.UPDATES_MALFORMED),
            Map.entry("tradebench_capture_tick_age_seconds", CollectorMetrics.TICK_AGE),
            Map.entry("tradebench_capture_bar_age_seconds", CollectorMetrics.BAR_AGE),
            Map.entry("tradebench_capture_queue_pending", CollectorMetrics.QUEUE_PENDING),
            Map.entry("tradebench_capture_tick_gap_seconds_bucket", CollectorMetrics.TICK_GAP),
            Map.entry("tradebench_events_total", CollectorMetrics.EVENTS),
            Map.entry("tradebench_gap_minutes_total", CollectorMetrics.GAP_MINUTES),
            Map.entry("tradebench_store_write_duration_seconds_bucket", CollectorMetrics.STORE_WRITE_DURATION),
            Map.entry("tradebench_jobs_runs_total", CollectorMetrics.JOB_RUNS),
            Map.entry("tradebench_jobs_duration_seconds_bucket", CollectorMetrics.JOB_DURATION),
            Map.entry("tradebench_heal_minutes_total", CollectorMetrics.HEAL_MINUTES),
            Map.entry("tradebench_heal_points_spent_total", CollectorMetrics.HEAL_POINTS_SPENT),
            Map.entry("tradebench_ig_allowance_remaining", CollectorMetrics.IG_ALLOWANCE_REMAINING));
    private static final Pattern SERIES_NAME = Pattern.compile("tradebench_[a-z_]+");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<Path> dashboards() throws IOException {
        try (Stream<Path> files = Files.list(DIR)) {
            return files.filter(f -> f.toString().endsWith(".json")).sorted().toList();
        }
    }

    private static List<String> expressions(JsonNode node) {
        List<String> found = new ArrayList<>();
        if (node.isObject()) {
            node.properties().forEach(e -> {
                if ((e.getKey().equals("expr") || e.getKey().equals("query")) && e.getValue().isTextual()) {
                    found.add(e.getValue().asText());
                }
                found.addAll(expressions(e.getValue()));
            });
        } else if (node.isArray()) {
            node.forEach(child -> found.addAll(expressions(child)));
        }
        return found;
    }

    @Test
    void thereAreTheTwoRuledDashboards() throws IOException {
        List<String> titles = new ArrayList<>();
        for (Path f : dashboards()) {
            JsonNode d = MAPPER.readTree(f.toFile());
            titles.add(d.get("title").asText());
            assertTrue(d.get("uid").asText().startsWith("tradebench-"), f + ": a stable uid, so a push updates it in place");
        }
        assertEquals(List.of("Collector — domain", "Collector — operations"), titles);
    }

    @Test
    void everySeriesAPanelQueriesIsOneTheCollectorEmits() throws IOException {
        Set<String> used = new TreeSet<>();
        for (Path f : dashboards()) {
            for (String expr : expressions(MAPPER.readTree(f.toFile()))) {
                Matcher m = SERIES_NAME.matcher(expr);
                while (m.find()) {
                    String series = m.group();
                    assertTrue(SERIES.containsKey(series), f.getFileName() + " queries " + series
                            + ", which the collector does not emit: " + expr);
                    used.add(SERIES.get(series));
                }
            }
        }
        assertEquals(new TreeSet<>(SERIES.values()), used, "every contract metric is on a dashboard somewhere");
    }

    /** The functions the panels may call — PromQL's and LogQL's, as used here. A typo becomes an unknown name. */
    private static final Set<String> FUNCTIONS = Set.of("sum", "max", "avg", "rate", "increase", "histogram_quantile", "vector",
            "count_over_time", "label_values");
    private static final Pattern CALL = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_.]*)\\s*\\(");

    @Test
    void everyFunctionAQueryCallsIsAKnownOne() throws IOException {
        for (Path f : dashboards()) {
            for (String expr : expressions(MAPPER.readTree(f.toFile()))) {
                Matcher m = CALL.matcher(expr);
                while (m.find()) {
                    String name = m.group(1);
                    if (name.equals("by") || name.equals("without")) {
                        continue; // grouping, not a call
                    }
                    assertTrue(FUNCTIONS.contains(name), f.getFileName() + " calls " + name + "(): " + expr);
                }
                assertEquals(expr.chars().filter(c -> c == '(').count(), expr.chars().filter(c -> c == ')').count(),
                        f.getFileName() + ": unbalanced parentheses in " + expr);
            }
        }
    }

    @Test
    void theDashboardsNameNoDataSourceAndNoInstanceOfTheirOwn() throws IOException {
        for (Path f : dashboards()) {
            String text = Files.readString(f);
            JsonNode d = MAPPER.readTree(text);
            assertFalse(text.contains("grafanacloud-"), f + ": the stack's own data-source names stay out of the repo");
            for (JsonNode uid : d.findValues("uid")) {
                if (uid.isTextual() && !uid.asText().startsWith("tradebench-")) {
                    assertTrue(Set.of("${prom}", "${logs}").contains(uid.asText()), f + ": a data source by variable, not " + uid);
                }
            }
            for (String expr : expressions(d)) {
                Matcher m = INSTANCE_MATCHER.matcher(expr);
                while (m.find()) {
                    assertEquals("instance=~\"$instance\"", m.group(), f + ": an instance only by the variable (G1): " + expr);
                }
            }
            for (JsonNode v : d.path("templating").path("list")) {
                if (v.path("name").asText().equals("instance")) {
                    assertTrue(v.path("current").isEmpty() && v.path("options").isEmpty(),
                            f + ": the instance variable saves no value of its own (G1): " + v);
                }
            }
        }
    }

    @Test
    void eachDataSourceVariablePicksItsSourceByNameNotByAlphabet() throws IOException {
        // live 2026-10-31: with no saved value, ${logs} took the first Loki source A–Z, the stack's alert history
        for (Path f : dashboards()) {
            for (JsonNode v : MAPPER.readTree(f.toFile()).path("templating").path("list")) {
                if (v.path("type").asText().equals("datasource")) {
                    String expected = Map.of("prom", "/-prom$/", "logs", "/-logs$/").get(v.path("name").asText());
                    assertEquals(expected, v.path("regex").asText(null), f + ": " + v);
                }
            }
        }
    }

    @Test
    void aStatPanelReadsZeroNotNoDataWhenNothingHappened() throws IOException {
        // live 2026-10-31: no ERROR event has ever happened, so its series does not exist and a bare sum is empty
        for (Path f : dashboards()) {
            for (JsonNode panel : MAPPER.readTree(f.toFile()).path("panels")) {
                if (panel.path("type").asText().equals("stat")) {
                    for (String expr : expressions(panel)) {
                        for (String part : expr.split(" \\+ ")) {
                            assertTrue(part.endsWith("or vector(0)") || part.endsWith("or vector(0))"),
                                    f + " " + panel.path("title").asText() + ": " + expr);
                        }
                    }
                }
            }
        }
    }

    /** Any matcher on an instance label, whatever its operator — the guard does not trust a value it has not seen. */
    private static final Pattern INSTANCE_MATCHER = Pattern.compile("\\b(service_)?instance(_id)?\\s*(=~|!~|!=|=)\\s*\"[^\"]*\"");
}
