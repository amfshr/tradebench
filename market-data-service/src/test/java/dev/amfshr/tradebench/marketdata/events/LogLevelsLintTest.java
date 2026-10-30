package dev.amfshr.tradebench.marketdata.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * D37's convention, enforced on the sources: a log line that reports a failure carries its level
 * ({@link LogLine}), so Loki's level filter finds it. A new untagged {@code "… failed"} line fails here
 * rather than arriving in production as INFO.
 */
class LogLevelsLintTest {

    private static final Path SOURCES = Path.of("src/main/java");
    private static final Pattern LOG_CALL = Pattern.compile("\\b(?:log|accept)\\(");
    private static final Pattern WARNING_WORDS = Pattern.compile(
            "failed|refused|lost|shedding|not written|unverifiable|re-acquired|abandoned|did not stop|interrupted");

    @Test
    void everyLogLineThatReportsAFailureCarriesItsLevel() throws IOException {
        List<String> untagged = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                Matcher call = LOG_CALL.matcher(source);
                while (call.find()) {
                    String arguments = arguments(source, call.end());
                    if (WARNING_WORDS.matcher(literals(arguments)).find()) {
                        checked++;
                        if (!arguments.contains("LogLine.") && !arguments.contains("\"FATAL")) {
                            untagged.add(file.getFileName() + ": " + arguments.strip());
                        }
                    }
                }
            }
        }
        assertTrue(checked > 20, "the scan found the tagged lines it exists to keep tagged: " + checked);
        assertEquals(List.of(), untagged);
    }

    /** The text up to the call's closing parenthesis, string literals skipped over whole. */
    private static String arguments(String source, int from) {
        int depth = 1;
        boolean inString = false;
        for (int i = from; i < source.length(); i++) {
            char c = source.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return source.substring(from, i);
            }
        }
        throw new AssertionError("an unclosed call at " + from);
    }

    private static String literals(String arguments) {
        StringBuilder text = new StringBuilder();
        Matcher literal = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(arguments);
        while (literal.find()) {
            text.append(literal.group(1)).append(' ');
        }
        return text.toString();
    }
}
