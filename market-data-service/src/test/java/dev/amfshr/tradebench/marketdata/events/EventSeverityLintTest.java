package dev.amfshr.tradebench.marketdata.events;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * B29's guarantee kept (E1-T17): the collector's metrics start every event series at zero, at the severity it will be
 * recorded at — the type's own, which {@link ServiceEvent#of} always uses, plus the exceptions built directly. Each
 * exception must be pre-registered in {@code CollectorMetrics.startAtZero}, or its first count after a restart is lost
 * to {@code increase()} again; a new one fails here until it is.
 */
class EventSeverityLintTest {

    private static final Pattern DIRECT = Pattern.compile("new ServiceEvent\\(\\s*EventType\\.([A-Z_]+)");

    @Test
    void theOnlyEventBuiltAtAnotherSeverityIsTheReplayedReconnect() throws IOException {
        List<String> direct = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (file.getFileName().toString().equals("ServiceEvent.java")) {
                    continue;
                }
                Matcher m = DIRECT.matcher(Files.readString(file));
                while (m.find()) {
                    direct.add(file.getFileName() + " " + m.group(1));
                }
            }
        }
        assertEquals(List.of("Supervisor.java RECONNECT"), direct,
                "an event built at a non-default severity needs its series pre-registered in CollectorMetrics.startAtZero");
    }
}
