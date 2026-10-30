package dev.amfshr.tradebench.marketdata.events;

/**
 * A console line's level, carried in its text (E1-T16): {@code WARN …} and {@code ERROR …} lead the line,
 * and a {@code FATAL…} line is an error by its own word. Every producer keeps its plain
 * {@code Consumer<String>}; {@code Main} reads the level back and logs at it, so Grafana can filter by level.
 * An untagged line is INFO.
 */
public final class LogLine {

    private LogLine() {
    }

    public static String warn(String message) {
        return "WARN " + message;
    }

    public static String error(String message) {
        return "ERROR " + message;
    }

    /** The line at an event's severity — how a belt event reaches the log at the level it was recorded at. */
    public static String at(Severity severity, String message) {
        return severity == Severity.INFO ? message : severity.name() + " " + message;
    }

    public static Severity severityOf(String line) {
        if (line.startsWith("ERROR ") || line.startsWith("FATAL")) {
            return Severity.ERROR;
        }
        return line.startsWith("WARN ") ? Severity.WARN : Severity.INFO;
    }

    /** The line without its level tag (a {@code FATAL…} line keeps its word). */
    public static String text(String line) {
        if (line.startsWith("ERROR ")) {
            return line.substring("ERROR ".length());
        }
        return line.startsWith("WARN ") ? line.substring("WARN ".length()) : line;
    }
}
