package dev.amfshr.tradebench.marketdata.capture;

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

import dev.amfshr.tradebench.core.time.SystemClock;
import dev.amfshr.tradebench.ig.IgCredentials;
import dev.amfshr.tradebench.ig.IgEnvironment;
import dev.amfshr.tradebench.ig.error.IgFatalConfigException;
import dev.amfshr.tradebench.ig.http.JdkHttpTransport;
import dev.amfshr.tradebench.ig.rest.RequestPacer;
import dev.amfshr.tradebench.ig.session.IgSession;
import dev.amfshr.tradebench.ig.session.IgSessionManager;
import dev.amfshr.tradebench.ig.session.LoginRateGate;
import dev.amfshr.tradebench.ig.stream.IgStreamClient;
import dev.amfshr.tradebench.ig.stream.IgStreamSession;
import dev.amfshr.tradebench.ig.stream.LightstreamerTransport;
import dev.amfshr.tradebench.ig.stream.StreamTransport;
import dev.amfshr.tradebench.ig.time.Sleeper;
import dev.amfshr.tradebench.marketdata.persistence.Database;
import dev.amfshr.tradebench.marketdata.persistence.DbSink;
import dev.amfshr.tradebench.marketdata.persistence.SingleInstanceLock;

import org.jspecify.annotations.Nullable;

/**
 * The capture entrypoint: session → stream → queues → sink, until Ctrl-C. Plain
 * env-configured main — Spring earns its way in at T6. TRADEBENCH_SINK picks jsonl (T3's
 * evidence files) or db (T4's real write path: Flyway-migrated Postgres behind the
 * single-instance advisory lock, taken BEFORE any IG contact).
 */
public final class CaptureRunner {

    private static final String USER = "default-user";
    private static final Duration HEARTBEAT = Duration.ofSeconds(60);

    private CaptureRunner() {
    }

    public static void main(String[] args) throws Exception {
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
        CaptureSink sink;
        switch (sinkKind) {
            case "jsonl" -> {
                Path captureDir = Path.of(required(env, "TRADEBENCH_CAPTURE_DIR"));
                Files.createDirectories(captureDir);
                Path file = captureDir.resolve("capture-" + DateTimeFormatter.ISO_INSTANT
                        .format(started.truncatedTo(ChronoUnit.SECONDS)).replace(":", "")
                        + ".jsonl");
                JsonlSink jsonl =
                        new JsonlSink(Files.newBufferedWriter(file, StandardCharsets.UTF_8));
                jsonl.writeMeta(USER, source, instance, epics, started);
                sink = jsonl;
                log(instance, "capturing to " + file.toAbsolutePath());
            }
            case "db" -> {
                String dbUrl = required(env, "TRADEBENCH_DB_URL");
                database = Database.connect(dbUrl, required(env, "TRADEBENCH_DB_USER"),
                        required(env, "TRADEBENCH_DB_PASSWORD"));
                lock = SingleInstanceLock.acquire(database);
                sink = new DbSink(database.dataSource(), USER, source, instance);
                log(instance, "capturing to " + dbUrl + " (migrated; advisory lock held)");
            }
            default -> throw new IgFatalConfigException(
                    "TRADEBENCH_SINK must be 'jsonl' or 'db', got '" + sinkKind + "'");
        }
        Database db = database;
        SingleInstanceLock instanceLock = lock;
        IgSessionManager sessions = new IgSessionManager(new JdkHttpTransport(),
                igEnv, credentials,
                new RequestPacer(RequestPacer.ACCOUNT_NON_TRADING_PER_MINUTE,
                        clock::monotonicNanos, Sleeper.SYSTEM),
                new LoginRateGate(clock::monotonicNanos, Sleeper.SYSTEM));
        IgSession session = sessions.current();
        log(instance, igEnv + " session on " + session.activeAccountId() + " via "
                + session.lightstreamerEndpoint());

        CaptureQueues queues = new CaptureQueues(CaptureQueues.DEFAULT_TICK_CAPACITY);
        CapturePump pump = new CapturePump(queues, sink, Sleeper.SYSTEM);
        Thread pumpThread = new Thread(pump, "capture-pump");

        IgStreamSession stream = new IgStreamClient(new LightstreamerTransport())
                .connect(session, queues, new StreamTransport.ConnectionListener() {
                    @Override
                    public void onStatusChange(String status) {
                        log(instance, "lightstreamer: " + status);
                    }

                    @Override
                    public void onServerError(int code, String message) {
                        log(instance, "lightstreamer SERVER ERROR " + code + ": " + message);
                    }
                });
        // E1 doctrine (the service's policy, not the client's): every market streams the
        // dual PRICE+CHART pair — ticks for precision, broker 1m bars for the healable record.
        for (String epic : epics) {
            stream.subscribePrice(epic, stateListener(instance, "PRICE " + epic));
            stream.subscribeChart1m(epic, stateListener(instance, "CHART " + epic));
        }
        pumpThread.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log(instance, "shutting down");
            stream.close();
            pump.stop();
            boolean pumpStopped = false;
            try {
                pumpStopped = pumpThread.join(Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (pumpStopped) {
                try {
                    sink.close();
                } catch (RuntimeException e) {
                    log(instance, "sink close failed: " + e);
                }
                closeQuietly(instance, instanceLock);
                closeQuietly(instance, db);
            } else {
                log(instance, "pump did not stop within 5s — leaving sink open to avoid a"
                        + " close/write race; file may miss its tail");
            }
            log(instance, summary(queues, pump));
        }, "capture-shutdown"));

        while (true) {
            Sleeper.SYSTEM.sleep(HEARTBEAT);
            if (!pumpThread.isAlive()) {
                log(instance, "FATAL: capture pump died — capture is void from here"
                        + (pump.failure() != null ? "; cause: " + pump.failure() : ""));
                System.exit(1);
            }
            log(instance, summary(queues, pump));
        }
    }

    private static StreamTransport.StateListener stateListener(String instance, String name) {
        return new StreamTransport.StateListener() {
            @Override
            public void onSubscribed() {
                log(instance, "subscribed: " + name);
            }

            @Override
            public void onSubscriptionError(int code, String message) {
                log(instance, "SUBSCRIPTION ERROR " + name + " " + code + ": " + message);
            }
        };
    }

    private static String summary(CaptureQueues queues, CapturePump pump) {
        return "ticks=" + queues.tickCount() + " bars=" + queues.barCount()
                + " written=" + pump.writtenCount()
                + " dropped=" + queues.droppedTicks() + " malformed=" + queues.malformedUpdates();
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
