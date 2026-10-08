package dev.amfshr.tradebench.marketdata.app;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import dev.amfshr.tradebench.core.time.SystemClock;
import dev.amfshr.tradebench.ig.IgCredentials;
import dev.amfshr.tradebench.ig.IgEnvironment;
import dev.amfshr.tradebench.ig.error.IgFatalConfigException;
import dev.amfshr.tradebench.ig.http.JdkHttpTransport;
import dev.amfshr.tradebench.ig.rest.IgRestClient;
import dev.amfshr.tradebench.ig.rest.RequestPacer;
import dev.amfshr.tradebench.ig.session.IgSessionManager;
import dev.amfshr.tradebench.ig.session.LoginRateGate;
import dev.amfshr.tradebench.ig.stream.LightstreamerTransport;
import dev.amfshr.tradebench.ig.time.Sleeper;
import dev.amfshr.tradebench.marketdata.store.Database;
import dev.amfshr.tradebench.marketdata.store.PostgresStore;
import dev.amfshr.tradebench.marketdata.store.SingleInstanceLock;

import org.jspecify.annotations.Nullable;
import dev.amfshr.tradebench.marketdata.store.JsonlStore;
import dev.amfshr.tradebench.marketdata.store.CaptureStore;
import dev.amfshr.tradebench.marketdata.store.EventLog;
import dev.amfshr.tradebench.marketdata.store.GapStore;
import dev.amfshr.tradebench.marketdata.store.StatusStore;
import dev.amfshr.tradebench.marketdata.store.PostgresObservabilityStore;
import dev.amfshr.tradebench.marketdata.ingest.Buffers;
import dev.amfshr.tradebench.marketdata.supervise.PacerDiscovery;
import dev.amfshr.tradebench.marketdata.supervise.Tuning;

/**
 * The capture entrypoint: session → stream → queues → sink, under the resilience belt, until
 * Ctrl-C. Plain env-configured main — Spring earns its way in at T6. TRADEBENCH_SINK picks
 * jsonl (T3's evidence files) or db (T4's real write path: Flyway-migrated Postgres behind the
 * single-instance advisory lock, taken BEFORE any IG contact). The pipeline itself is composed by
 * {@link CaptureAssembly} — shared with the scenario harness, so the wiring here is the wiring
 * the tests drive. The supervisor runs in both modes — resilience is sink-independent; in jsonl
 * its events simply have no home (decision #1).
 */
public final class Main {

    private static final String USER = "default-user";

    private Main() {
    }

    static void main() throws Exception {
        Map<String, String> env = System.getenv();
        String instance = required(env, "TRADEBENCH_INSTANCE");
        List<String> epics = Arrays.stream(required(env, "TRADEBENCH_EPICS").split(","))
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
        IgEnvironment igEnv = igEnvironment(required(env, "TRADEBENCH_IG_ENV"));
        String source = "ig-stream-" + igEnv.name().toLowerCase(java.util.Locale.ROOT);
        String sinkKind = required(env, "TRADEBENCH_SINK").strip().toLowerCase(java.util.Locale.ROOT);
        IgCredentials credentials = IgCredentials.fromEnv(env, igEnv);

        SystemClock clock = new SystemClock();
        Instant started = clock.wallInstant();

        Database database = null;
        SingleInstanceLock lock = null;
        CaptureStore sink;
        EventLog eventLog;
        GapStore gaps;
        StatusStore statusStore;
        switch (sinkKind) {
            case "jsonl" -> {
                Path captureDir = Path.of(required(env, "TRADEBENCH_CAPTURE_DIR"));
                Files.createDirectories(captureDir);
                Path file = captureDir.resolve("capture-" + DateTimeFormatter.ISO_INSTANT
                        .format(started.truncatedTo(ChronoUnit.SECONDS)).replace(":", "")
                        + ".jsonl");
                JsonlStore jsonl =
                        new JsonlStore(Files.newBufferedWriter(file, StandardCharsets.UTF_8));
                jsonl.writeMeta(USER, source, instance, epics, started);
                sink = jsonl;
                // jsonl is market-data-only (decision #1): gaps and service events have no home
                // here, so they are discarded — the db sink is the one that persists observability.
                eventLog = event -> { };
                gaps = gap -> { };
                statusStore = status -> { };
                log(instance, "capturing to " + file.toAbsolutePath()
                        + " (jsonl: gaps + service events are not persisted)");
            }
            case "db" -> {
                String dbUrl = required(env, "TRADEBENCH_DB_URL");
                database = Database.connect(dbUrl, required(env, "TRADEBENCH_DB_USER"),
                        required(env, "TRADEBENCH_DB_PASSWORD"));
                lock = SingleInstanceLock.acquire(database);
                sink = new PostgresStore(database.dataSource(), USER, source);
                PostgresObservabilityStore observability = new PostgresObservabilityStore(
                        database.dataSource(), USER, source, instance);
                eventLog = observability;
                gaps = observability;
                statusStore = observability;
                log(instance, "capturing to " + dbUrl + " (migrated; advisory lock held)");
            }
            default -> throw new IgFatalConfigException(
                    "TRADEBENCH_SINK must be 'jsonl' or 'db', got '" + sinkKind + "'");
        }
        Database db = database;
        SingleInstanceLock instanceLock = lock;
        JdkHttpTransport http = new JdkHttpTransport();
        RequestPacer pacer = new RequestPacer(RequestPacer.CONSERVATIVE_START,
                clock::monotonicNanos, Sleeper.SYSTEM); // discovery sets the real budget after login
        IgSessionManager sessions = new IgSessionManager(http, igEnv, credentials, pacer,
                new LoginRateGate(clock::monotonicNanos, Sleeper.SYSTEM));
        IgRestClient rest = new IgRestClient(http, igEnv, credentials, pacer);

        CaptureAssembly capture = CaptureAssembly.compose(new CaptureAssembly.Ports(instance, epics,
                clock, Sleeper.SYSTEM, () -> 0.5 + ThreadLocalRandom.current().nextDouble(),
                Tuning.playbook(), sessions, new LightstreamerTransport(), sink, eventLog, gaps,
                statusStore, message -> log(instance, message),
                () -> exitLoud(instance, "FATAL: recovery exhausted — the feed is dead"),
                cause -> exitLoud(instance, "FATAL: capture pump died — capture is void from here;"
                        + " cause: " + cause),
                Buffers.DEFAULT_TICK_CAPACITY));
        capture.boot();
        Thread pumpThread = new Thread(capture.pump, "capture-pump");
        Thread supervisorThread = new Thread(capture.supervisor, "capture-supervisor");
        pumpThread.start();
        supervisorThread.start();
        new PacerDiscovery(rest::applicationAllowance, pacer::setPerMinute, eventLog,
                message -> log(instance, message), clock).discover(sessions.current());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log(instance, "shutting down");
            boolean pumpStopped = capture.shutdown(
                    () -> {
                        supervisorThread.interrupt();
                        return join(supervisorThread);
                    },
                    () -> join(pumpThread));
            if (pumpStopped) {
                closeQuietly(instance, instanceLock);
                closeQuietly(instance, db);
            }
            log(instance, capture.summary());
        }, "capture-shutdown"));

        while (true) {
            Sleeper.SYSTEM.sleep(CaptureAssembly.HEARTBEAT);
            if (!pumpThread.isAlive()) { // the backstop — onPumpDeath has normally exited already
                log(instance, "FATAL: capture pump died — capture is void from here"
                        + (capture.pump.failure() != null ? "; cause: " + capture.pump.failure() : ""));
                System.exit(1);
            }
            if (!supervisorThread.isAlive()) {
                log(instance, "FATAL: capture supervisor died — resilience is void from here");
                System.exit(1);
            }
            capture.probe.publish();
            log(instance, capture.summary());
        }
    }

    /** Fail loud and leave: the process supervisor restarts us. Off the calling thread, because
     * the shutdown hook joins the worker threads and a thread parked inside Runtime.exit never
     * finishes — the join would time out every time. */
    private static void exitLoud(String instance, String message) {
        log(instance, message + "; exiting for the process supervisor to restart");
        new Thread(() -> System.exit(1), "capture-exit").start();
    }

    private static boolean join(Thread thread) {
        try {
            return thread.join(Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void closeQuietly(String instance, @Nullable AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            log(instance, "close failed: " + e);
        }
    }

    private static IgEnvironment igEnvironment(String value) {
        return switch (value.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "demo" -> IgEnvironment.DEMO;
            case "live" -> IgEnvironment.LIVE;
            default -> throw new IgFatalConfigException(
                    "TRADEBENCH_IG_ENV must be 'demo' or 'live', got '" + value + "'");
        };
    }

    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) {
            throw new IgFatalConfigException("Missing required environment variable '" + key
                    + "' — the runner names its instance and its outputs before acting");
        }
        return value;
    }

    private static void log(String instance, String message) {
        System.out.println(Instant.now() + " [" + instance + "] " + message);
    }
}
