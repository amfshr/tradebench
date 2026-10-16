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
}
