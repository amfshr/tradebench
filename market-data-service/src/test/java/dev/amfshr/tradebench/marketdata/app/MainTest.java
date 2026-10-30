package dev.amfshr.tradebench.marketdata.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.ig.error.IgFatalConfigException;

/** The shutdown hook is registered before boot (E1-T10 #24): its joins must survive the window. */
class MainTest {

    @Test
    void joiningAThreadThatWasNeverStartedReturnsAtOnce() {
        // A SIGTERM during the boot budget finds the worker threads created but not started; the
        // JDK's timed join throws on such a thread, which would end the hook before it closed anything.
        Thread neverStarted = new Thread(() -> { }, "capture-pump");

        assertTrue(Main.join(neverStarted));
    }

    @Test
    void joiningAFinishedThreadReportsItStopped() throws InterruptedException {
        Thread finished = new Thread(() -> { }, "capture-pump");
        finished.start();
        finished.join();

        assertTrue(Main.join(finished));
    }

    @Test
    void anInstanceWithoutAPlatformUserRefusesToStart() {
        // named by the environment, never defaulted in code (E1-T14, D31)
        IgFatalConfigException thrown = assertThrows(IgFatalConfigException.class,
                () -> Main.platformUser(Map.of("TRADEBENCH_INSTANCE", "capture-demo-01")));
        assertTrue(thrown.getMessage().contains("'TRADEBENCH_USER'"), "the missing key is named");
        assertThrows(IgFatalConfigException.class, () -> Main.platformUser(Map.of("TRADEBENCH_USER", "  ")),
                "blank is missing");
    }

    @Test
    void thePlatformUserIsTheEnvironmentsWord() {
        assertEquals("a-synthetic-owner", Main.platformUser(Map.of("TRADEBENCH_USER", "a-synthetic-owner")));
    }

    @Test
    void noArchiveSettingsLeaveTheArchiveOff() {
        assertEquals(null, Main.archiveConfig(Map.of("TRADEBENCH_USER", "platform")));
    }

    @Test
    void halfAnArchiveConfigurationRefusesToStartNamingWhatIsMissing() {
        IgFatalConfigException thrown = assertThrows(IgFatalConfigException.class, () -> Main.archiveConfig(Map.of(
                "TRADEBENCH_ARCHIVE_ENDPOINT", "https://bucket.example", "TRADEBENCH_ARCHIVE_BUCKET", "archive")));
        assertTrue(thrown.getMessage().contains("TRADEBENCH_ARCHIVE_ACCESS_KEY_ID"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("TRADEBENCH_ARCHIVE_REGION"));
    }

    @Test
    void theFullArchiveConfigurationIsTheEnvironmentsWord() {
        Map<String, String> env = new java.util.HashMap<>();
        for (String key : Main.ARCHIVE_KEYS) {
            env.put(key, "a-synthetic-" + key.toLowerCase());
        }
        env.put("TRADEBENCH_ARCHIVE_ENDPOINT", "https://bucket.example");
        Main.ArchiveConfig config = Main.archiveConfig(env);
        assertEquals("https://bucket.example", config.endpoint().toString());
        assertEquals("a-synthetic-tradebench_archive_bucket", config.bucket());
        assertEquals("a-synthetic-tradebench_archive_secret_access_key", config.secretAccessKey());
    }

    @Test
    void theHealsRetryTakesTheSessionsFailurePathAndItsFirstAskTheCurrentSession() throws Exception {
        // the re-review's N7: swapping these would reuse a dead token on every retry
        dev.amfshr.tradebench.marketdata.testutil.FakeSessions sessions = new dev.amfshr.tradebench.marketdata.testutil.FakeSessions();
        Main.healSessions(sessions).get(false);
        assertEquals(0, sessions.reLogins, "a first ask takes the current session");
        Main.healSessions(sessions).get(true);
        assertEquals(1, sessions.reLogins, "a retry takes the failure path");
    }

    @Test
    void migrationCredentialsGoTogether() {
        assertEquals(null, Main.migrator(Map.of()), "neither: the service's own login migrates");
        assertThrows(IgFatalConfigException.class, () -> Main.migrator(Map.of("TRADEBENCH_DB_MIGRATE_USER", "owner")));
        assertThrows(IgFatalConfigException.class, () -> Main.migrator(Map.of("TRADEBENCH_DB_MIGRATE_PASSWORD", "x")));
        assertEquals("a-synthetic-owner", Main.migrator(Map.of("TRADEBENCH_DB_MIGRATE_USER", "a-synthetic-owner",
                "TRADEBENCH_DB_MIGRATE_PASSWORD", "x")).user());
    }

    @Test
    void theLibrariesSlf4jLogsReachJavaLoggingNotTheVoid() {
        // E1-T16: HikariCP and the AWS SDK log through SLF4J; with no binding their warnings were dropped
        // ("No SLF4J providers were found"). The JUL binding routes them to the console and the agent.
        assertTrue(org.slf4j.LoggerFactory.getILoggerFactory().getClass().getName().contains("JDK14"),
                "SLF4J is bound to java.util.logging: " + org.slf4j.LoggerFactory.getILoggerFactory().getClass().getName());
    }

    @Test
    void aLineIsExportedAtItsOwnLevelWithoutItsTag() {
        // E1-T16: Grafana filters by level, so a warning or an error must not arrive as INFO
        java.util.List<java.util.logging.LogRecord> seen = new java.util.ArrayList<>();
        java.util.logging.Handler capture = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                seen.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Main.LOG.addHandler(capture);
        try {
            Main.log("capture-demo-01", "boot");
            Main.log("capture-demo-01", dev.amfshr.tradebench.marketdata.events.LogLine.warn("sink unavailable"));
            Main.log("capture-demo-01", dev.amfshr.tradebench.marketdata.events.LogLine.error("jobs: heal failed"));
            Main.log("capture-demo-01", "FATAL: recovery exhausted");
        } finally {
            Main.LOG.removeHandler(capture);
        }
        assertEquals(java.util.List.of(java.util.logging.Level.INFO, java.util.logging.Level.WARNING,
                java.util.logging.Level.SEVERE, java.util.logging.Level.SEVERE), seen.stream().map(java.util.logging.LogRecord::getLevel).toList());
        assertEquals(java.util.List.of("[capture-demo-01] boot", "[capture-demo-01] sink unavailable",
                "[capture-demo-01] jobs: heal failed", "[capture-demo-01] FATAL: recovery exhausted"),
                seen.stream().map(java.util.logging.LogRecord::getMessage).toList(), "the level is the record's, not its text's");
    }

    @Test
    void theCollectorsLinesGoThroughJavaLoggingSoTheAgentCanExportThem() {
        // E1-T7, in-ticket ruling 2: the OpenTelemetry agent exports java.util.logging records, not println
        java.util.List<java.util.logging.LogRecord> seen = new java.util.ArrayList<>();
        java.util.logging.Handler capture = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                seen.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Main.LOG.addHandler(capture);
        try {
            Main.log("capture-demo-01", "boot");
        } finally {
            Main.LOG.removeHandler(capture);
        }
        assertEquals(1, seen.size());
        assertEquals("[capture-demo-01] boot", seen.getFirst().getMessage());
        assertEquals(java.util.logging.Level.INFO, seen.getFirst().getLevel());
    }

    @Test
    void aLineReachesTheConsoleEvenAfterTheLoggingSystemShutsDown() throws Exception {
        // the doctrine review's finding 2: at exit the JDK's logging hook closes every handler — a console
        // handler would close System.out and lose the shutdown hook's own last lines
        java.io.PrintStream real = System.out;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            java.util.logging.LogManager.getLogManager().reset(); // what the JDK's shutdown hook does
            Main.log("capture-demo-01", "shutting down");
        } finally {
            System.setOut(real);
            java.util.logging.LogManager.getLogManager().readConfiguration(); // the rest of the JVM's tests get logging back
        }
        assertTrue(captured.toString(java.nio.charset.StandardCharsets.UTF_8).contains("[capture-demo-01] shutting down"));
    }

    @Test
    void migrationCredentialsNeverPrintTheirPassword() {
        assertEquals("Credentials[user=owner, password=***]", new dev.amfshr.tradebench.marketdata.store.Database
                .Credentials("owner", "a-synthetic-secret").toString());
    }
}
