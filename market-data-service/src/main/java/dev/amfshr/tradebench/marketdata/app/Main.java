package dev.amfshr.tradebench.marketdata.app;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.ThreadLocalRandom;

import io.opentelemetry.api.GlobalOpenTelemetry;

import dev.amfshr.tradebench.core.time.SystemClock;
import dev.amfshr.tradebench.ig.IgCredentials;
import dev.amfshr.tradebench.ig.IgEnvironment;
import dev.amfshr.tradebench.ig.error.IgFatalConfigException;
import dev.amfshr.tradebench.ig.http.JdkHttpTransport;
import dev.amfshr.tradebench.ig.rest.IgRestClient;
import dev.amfshr.tradebench.ig.rest.RequestPacer;
import dev.amfshr.tradebench.ig.session.IgSessionManager;
import dev.amfshr.tradebench.ig.session.IgSessions;
import dev.amfshr.tradebench.ig.session.LoginRateGate;
import dev.amfshr.tradebench.ig.stream.LightstreamerTransport;
import dev.amfshr.tradebench.ig.time.Sleeper;
import dev.amfshr.tradebench.marketdata.metrics.JobMetrics;
import dev.amfshr.tradebench.marketdata.events.LogLine;
import dev.amfshr.tradebench.marketdata.store.BestEffortEventLog;
import dev.amfshr.tradebench.marketdata.store.Database;
import dev.amfshr.tradebench.marketdata.store.PostgresStore;
import dev.amfshr.tradebench.marketdata.heal.Healer;
import dev.amfshr.tradebench.marketdata.heal.IgHistory;
import dev.amfshr.tradebench.marketdata.heal.RetryingCandleSource;
import dev.amfshr.tradebench.marketdata.jobs.DailyJobs;
import dev.amfshr.tradebench.marketdata.jobs.JobRunStore;
import dev.amfshr.tradebench.marketdata.jobs.JobTuning;
import dev.amfshr.tradebench.marketdata.jobs.JobsRunner;
import dev.amfshr.tradebench.marketdata.store.PostgresHealStore;
import dev.amfshr.tradebench.marketdata.store.PostgresJobRunStore;
import dev.amfshr.tradebench.marketdata.archive.Archiver;
import dev.amfshr.tradebench.marketdata.archive.S3ObjectStore;
import dev.amfshr.tradebench.marketdata.jobs.DayPhase;
import dev.amfshr.tradebench.marketdata.store.PostgresArchiveStore;
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

    private Main() {
    }

    static void main() throws Exception {
        Map<String, String> env = System.getenv();
        String instance = required(env, "TRADEBENCH_INSTANCE");
        String user = platformUser(env);
        List<String> epics = Arrays.stream(required(env, "TRADEBENCH_EPICS").split(","))
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
        IgEnvironment igEnv = igEnvironment(required(env, "TRADEBENCH_IG_ENV"));
        String source = "ig-stream-" + igEnv.name().toLowerCase(java.util.Locale.ROOT);
        String restSource = "ig-rest-" + igEnv.name().toLowerCase(java.util.Locale.ROOT); // the heal's bars (P8)
        // the agent's meter when it is attached (OTEL_EXPORTER_OTLP_ENDPOINT set); a no-op otherwise (E1-T16)
        CaptureAssembly.Telemetry telemetry =
                new CaptureAssembly.Telemetry(GlobalOpenTelemetry.get().getMeter("tradebench.market-data"), user, source);
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
                jsonl.writeMeta(user, source, instance, epics, started);
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
                        required(env, "TRADEBENCH_DB_PASSWORD"), migrator(env));
                lock = SingleInstanceLock.acquire(database);
                sink = new PostgresStore(database.dataSource(), user, source);
                PostgresObservabilityStore observability = new PostgresObservabilityStore(
                        database.dataSource(), user, source, instance);
                eventLog = observability;
                gaps = observability;
                statusStore = observability;
                log(instance, "capturing to the configured database (migrated; advisory lock held)"); // never the URL: an .env value (G1)
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
        // The daily heal (E1-T6): db mode only — it reads and writes the record. Stopped first at
        // shutdown, so a heal in flight is interrupted before the stores it writes to close.
        ArchiveConfig archive = archiveConfig(env);
        JobsRunner jobs = null;
        if (db != null) {
            List<DayPhase> phases = new ArrayList<>();
            phases.add(new Healer(new PostgresHealStore(db.dataSource(), user, source, restSource, instance),
                    new RetryingCandleSource(new IgHistory(healSessions(sessions), rest::minuteCandlesBetween), 3,
                            Duration.ofSeconds(2)),
                    clock, 720, HEAL_ALLOWANCE_RESERVE));
            if (archive != null) {
                phases.add(new Archiver(new PostgresArchiveStore(db.dataSource(), user, source),
                        new S3ObjectStore(archive.endpoint(), archive.region(), archive.bucket(), archive.accessKeyId(),
                                archive.secretAccessKey()), user + "/" + source));
                log(instance, "daily job: heal, then archive to the configured bucket"); // never its name: an .env value (G1)
            } else {
                log(instance, "daily job: heal only — no TRADEBENCH_ARCHIVE_* set, so nothing is archived off the box");
            }
            jobs = new JobsRunner(new DailyJobs(clock, epics, phases,
                    countedRuns(telemetry, new PostgresJobRunStore(db.dataSource(), user, source, instance)),
                    JobTuning.playbook(),
                    message -> log(instance, message)), JobTuning.playbook().wake(), message -> log(instance, message));
        }
        JobsRunner dailyJobs = jobs;

        CaptureAssembly capture = CaptureAssembly.compose(new CaptureAssembly.Ports(instance, epics,
                clock, Sleeper.SYSTEM, () -> 0.5 + ThreadLocalRandom.current().nextDouble(),
                Tuning.playbook(), sessions, new LightstreamerTransport(), sink, eventLog, gaps,
                statusStore, message -> log(instance, message),
                () -> exitLoud(instance, "FATAL: recovery exhausted — the feed is dead"),
                cause -> exitLoud(instance, "FATAL: capture pump died — capture is void from here;"
                        + " cause: " + cause),
                Buffers.DEFAULT_TICK_CAPACITY,
                telemetry));
        Thread pumpThread = new Thread(capture.pump, "capture-pump");
        Thread supervisorThread = new Thread(capture.supervisor, "capture-supervisor");
        Thread eventsThread = new Thread(capture.events, "capture-events");
        // Registered before boot: a SIGTERM during a slow start still closes the stream and the sink;
        // a thread never started joins at once (E1-T10 #24).
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log(instance, "shutting down");
            if (dailyJobs != null) {
                try {
                    if (!dailyJobs.stop(Duration.ofSeconds(5))) {
                        log(instance, LogLine.warn("jobs did not stop within 5s — a heal may be mid-write; its row is not recorded"));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            boolean pumpStopped = capture.shutdown(
                    () -> {
                        supervisorThread.interrupt();
                        return join(supervisorThread);
                    },
                    () -> join(pumpThread),
                    () -> join(eventsThread));
            if (pumpStopped) {
                closeQuietly(instance, instanceLock);
                closeQuietly(instance, db);
            }
            log(instance, capture.summary());
        }, "capture-shutdown"));

        capture.boot();
        pumpThread.start();
        supervisorThread.start();
        eventsThread.start();
        if (dailyJobs != null) {
            dailyJobs.start();
        }
        // Log-only: discovery runs once, here at boot, so a refused breadcrumb is a console line,
        // never a heartbeat term (doctrine review 2026-10-10, finding 3).
        new PacerDiscovery(rest::applicationAllowance, pacer::setPerMinute,
                new BestEffortEventLog(capture.metrics.counting(eventLog), message -> log(instance, message)),
                message -> log(instance, message), clock).discover(sessions.current());

        URI pingTo = pingUrl(env);
        DeadManPing ping = pingTo == null ? null : new DeadManPing(pingTo, message -> log(instance, message));
        log(instance, pingTo == null ? "dead-man ping off — no TRADEBENCH_PING_URL set"
                : "dead-man ping on — once a heartbeat");
        Heartbeat heartbeat = new Heartbeat(instanceLock == null ? null : () -> verifyLock(instance, instanceLock),
                capture.probe::publish, ping == null ? null : ping::ping, capture::summary,
                message -> log(instance, message));
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
            heartbeat.beat();
        }
    }

    /** The lock's session dies with the database restart the sink holds through (D28); a live
     * session still holds it, a dead one is replaced, a refusal means another instance took it while
     * ours was gone — fail closed, the newcomer runs (E1-T13, re-review finding 1). */
    private static void verifyLock(String instance, SingleInstanceLock lock) {
        try {
            switch (lock.verify()) {
                case HELD -> { }
                case REACQUIRED -> log(instance, LogLine.warn("advisory lock re-acquired on a fresh session — the"
                        + " database restarted under us and took the old session with it"));
                case UNVERIFIABLE -> log(instance, LogLine.warn("advisory lock unverifiable — the database is"
                        + " unreachable, so nobody can take it either; asking again next heartbeat"));
            }
        } catch (IllegalStateException refused) {
            log(instance, "FATAL: " + refused.getMessage()
                    + " — it was taken while our session was gone");
            System.exit(1);
        }
    }

    /** Fail loud and leave: the process supervisor restarts us. Off the calling thread, because
     * the shutdown hook joins the worker threads and a thread parked inside Runtime.exit never
     * finishes — the join would time out every time. */
    private static void exitLoud(String instance, String message) {
        log(instance, message + "; exiting for the process supervisor to restart");
        new Thread(() -> System.exit(1), "capture-exit").start();
    }

    /** Up to five seconds. A thread never started — a SIGTERM during boot — has nothing to wait
     * for; asking the JDK to join it throws, which would end the hook before it closed anything. */
    static boolean join(Thread thread) {
        if (thread.getState() == Thread.State.NEW) {
            return true;
        }
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
            log(instance, LogLine.warn("close failed: " + e));
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

    /** Where the daily archive goes: a bucket over the S3 protocol (E1-T6). All five settings or none —
     * none leaves the archive off and says so; some is a mistake, refused before anything runs. */
    record ArchiveConfig(URI endpoint, String region, String bucket, String accessKeyId, String secretAccessKey) {
    }

    /** The heal's sessions: the current one, or — on a retry — the failure path, which validates the token
     * and logs in fresh only if it is dead (a retry must never reuse a dead session). */
    /** The job-run record, counted on its way in (E1-T16): the jobs panels read what this wrapper sees. */
    static JobRunStore countedRuns(CaptureAssembly.Telemetry telemetry, JobRunStore store) {
        return new JobMetrics(telemetry.meter(), telemetry.user(), telemetry.source()).counting(store);
    }

    static IgHistory.Sessions healSessions(IgSessions sessions) {
        return afterFailure -> afterFailure ? sessions.afterFailure() : sessions.current();
    }

    /** The owner's login for migrations, when the service itself runs as the DML-only app role (E1-T7):
     * both or neither — one without the other is a mistake, refused before anything runs. */
    static Database.@Nullable Credentials migrator(Map<String, String> env) {
        String user = env.get("TRADEBENCH_DB_MIGRATE_USER");
        String password = env.get("TRADEBENCH_DB_MIGRATE_PASSWORD");
        boolean hasUser = user != null && !user.isBlank();
        boolean hasPassword = password != null && !password.isBlank();
        if (hasUser != hasPassword) {
            throw new IgFatalConfigException("TRADEBENCH_DB_MIGRATE_USER and TRADEBENCH_DB_MIGRATE_PASSWORD go together"
                    + " — set both, or neither to migrate with the service's own login");
        }
        return hasUser ? new Database.Credentials(user, password) : null;
    }

    /** Points of the week's 10,000 the daily heal never spends: kept for manual heals (E9-T8) and anything
     * else that shares the key's historical allowance. */
    static final long HEAL_ALLOWANCE_RESERVE = 2_000;

    static final List<String> ARCHIVE_KEYS = List.of("TRADEBENCH_ARCHIVE_ENDPOINT", "TRADEBENCH_ARCHIVE_REGION",
            "TRADEBENCH_ARCHIVE_BUCKET", "TRADEBENCH_ARCHIVE_ACCESS_KEY_ID", "TRADEBENCH_ARCHIVE_SECRET_ACCESS_KEY");

    static @Nullable ArchiveConfig archiveConfig(Map<String, String> env) {
        List<String> missing = ARCHIVE_KEYS.stream().filter(k -> env.get(k) == null || env.get(k).isBlank()).toList();
        if (missing.size() == ARCHIVE_KEYS.size()) {
            return null;
        }
        if (!missing.isEmpty()) {
            throw new IgFatalConfigException("the archive's bucket is half configured — set " + missing
                    + " too, or none of TRADEBENCH_ARCHIVE_*");
        }
        return new ArchiveConfig(URI.create(env.get("TRADEBENCH_ARCHIVE_ENDPOINT")), env.get("TRADEBENCH_ARCHIVE_REGION"),
                env.get("TRADEBENCH_ARCHIVE_BUCKET"), env.get("TRADEBENCH_ARCHIVE_ACCESS_KEY_ID"),
                env.get("TRADEBENCH_ARCHIVE_SECRET_ACCESS_KEY"));
    }

    /** The user every row this instance writes belongs to — the admin-owned platform tier's owner
     * (`platform`, D31) until jobs carry real users. Named by the environment, never defaulted in
     * code: an instance must say whose rows it writes before it writes one. */
    static String platformUser(Map<String, String> env) {
        return required(env, "TRADEBENCH_USER");
    }

    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) {
            throw new IgFatalConfigException("Missing required environment variable '" + key
                    + "' — the runner names its instance and its outputs before acting");
        }
        return value;
    }

    /** Every line the collector says goes to two places (E1-T7, in-ticket ruling 2): the console, by
     * println as ever — which survives the JDK's logging shutdown hook, the one that closes every handler and
     * would take the shutdown hook's own last lines with it — and java.util.logging, handler-less, for the
     * OpenTelemetry agent to export to Grafana when it rides. */
    static final Logger LOG = exportOnlyLogger();

    private static Logger exportOnlyLogger() {
        Logger logger = Logger.getLogger("tradebench.market-data");
        logger.setUseParentHandlers(false); // no console handler: the console line is println's
        return logger;
    }

    /** The console line as given; the exported record at the line's own level (E1-T16 — {@link LogLine}). */
    static void log(String instance, String message) {
        System.out.println(Instant.now() + " [" + instance + "] " + message);
        Level level = switch (LogLine.severityOf(message)) {
            case ERROR -> Level.SEVERE;
            case WARN -> Level.WARNING;
            case INFO -> Level.INFO;
        };
        LOG.log(level, "[" + instance + "] " + LogLine.text(message));
    }

    /** The dead-man ping's URL, if this instance has one (D33): absent leaves the ping off, said at boot. */
    static @Nullable URI pingUrl(Map<String, String> env) {
        String url = env.get("TRADEBENCH_PING_URL");
        return url == null || url.isBlank() ? null : URI.create(url);
    }
}
