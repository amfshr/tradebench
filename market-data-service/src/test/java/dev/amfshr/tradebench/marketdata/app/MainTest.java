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
        // E1-T14 (D31): the user every row belongs to is named by the environment, never defaulted
        // in code — an instance must say whose rows it writes before it writes one (config discipline).
        IgFatalConfigException thrown = assertThrows(IgFatalConfigException.class,
                () -> Main.platformUser(Map.of("TRADEBENCH_INSTANCE", "capture-demo-01")));
        assertTrue(thrown.getMessage().contains("TRADEBENCH_USER"), "the missing key is named");
        assertThrows(IgFatalConfigException.class, () -> Main.platformUser(Map.of("TRADEBENCH_USER", "  ")),
                "blank is missing");
    }

    @Test
    void thePlatformUserIsTheEnvironmentsWord() {
        assertEquals("platform", Main.platformUser(Map.of("TRADEBENCH_USER", "platform")));
    }
}
