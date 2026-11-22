package dev.amfshr.tradebench.marketdata.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.sql.DataSource;

import dev.amfshr.tradebench.marketdata.archive.DayParquet;
import dev.amfshr.tradebench.marketdata.events.LogLine;
import dev.amfshr.tradebench.marketdata.store.Database;
import dev.amfshr.tradebench.marketdata.store.TickReload;

/**
 * {@code reload-ticks <archive root> <epic> <first day> <last day>} — a restore's second step (D39, E1-T17): the
 * archived ticks of each UTC day put back into the configured database, after {@code pg_restore} of a dump that
 * left them out. Reads {@code <root>/<user>/<source>/<epic>/<day>/ticks.parquet} — a local copy of the archive, the
 * layout the archiver writes. Exits non-zero, naming the day, if a day comes back short or the range holds no file.
 */
public final class ReloadTicks {

    private ReloadTicks() {
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> env = System.getenv();
        if (args.length != 4) {
            System.err.println("usage: reload-ticks <archive root> <epic> <first day> <last day>   (days as YYYY-MM-DD)");
            System.exit(2);
        }
        String instance = env.getOrDefault("TRADEBENCH_INSTANCE", "");
        if (instance.isBlank()) {
            System.err.println("reload-ticks: TRADEBENCH_INSTANCE is not set — every job names its instance before acting");
            System.exit(2);
        }
        Consumer<String> out = line -> System.out.println("[" + instance + "] " + line);
        out.accept("reload-ticks into the configured database"); // never its URL: an .env value (G1)
        try (Database db = Database.connect(Main.required(env, "TRADEBENCH_DB_URL"),
                Main.required(env, "TRADEBENCH_DB_USER"), Main.required(env, "TRADEBENCH_DB_PASSWORD"))) {
            boolean whole = run(Path.of(args[0]), Main.platformUser(env), args[1], LocalDate.parse(args[2]),
                    LocalDate.parse(args[3]), db.dataSource(), out);
            System.exit(whole ? 0 : 1);
        }
    }

    /** Reloads every archived day in the range; true only if there was at least one and every one came back whole. */
    static boolean run(Path root, String user, String epic, LocalDate first, LocalDate last, DataSource db,
            Consumer<String> out) throws IOException {
        TickReload reload = new TickReload(db, user, epic);
        Set<LocalDate> days = new TreeSet<>();
        boolean whole = true;
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            for (Path file : files(root.resolve(user), epic, day)) {
                String source = file.getParent().getParent().getParent().getFileName().toString();
                String what = "reload-ticks: " + epic + " " + day + " (" + source + ")";
                days.add(day);
                TickReload.Day result;
                try {
                    result = reload.reload(day, DayParquet.readTicks(file));
                } catch (RuntimeException e) { // refused — the day's transaction rolled back; the other days go on
                    whole = false;
                    out.accept(LogLine.error(what + " — refused, nothing written: " + e.getMessage()));
                    continue;
                }
                whole &= result.whole();
                String line = what + " — " + result.inFile() + " archived, " + result.inserted() + " inserted, "
                        + result.inDatabase() + " in the database";
                out.accept(result.whole() ? line : LogLine.error(line + " — SHORT: archived ticks are missing"));
            }
        }
        if (days.isEmpty()) {
            out.accept(LogLine.error("reload-ticks: no archived ticks for " + epic + " from " + first + " to " + last
                    + " under " + root));
            return false;
        }
        out.accept(whole ? "reload-ticks: " + days.size() + " days, every one whole"
                : LogLine.error("reload-ticks: " + days.size() + " days, some SHORT or refused"));
        return whole;
    }

    private static List<Path> files(Path userRoot, String epic, LocalDate day) throws IOException {
        if (!Files.isDirectory(userRoot)) {
            return List.of();
        }
        try (Stream<Path> sources = Files.list(userRoot)) {
            return sources.map(source -> source.resolve(epic).resolve(day.toString()).resolve("ticks.parquet"))
                    .filter(Files::isRegularFile).sorted().toList();
        }
    }
}
