package dev.amfshr.tradebench.marketdata.app;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

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
}
