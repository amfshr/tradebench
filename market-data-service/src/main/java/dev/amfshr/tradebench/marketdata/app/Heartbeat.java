package dev.amfshr.tradebench.marketdata.app;

import java.util.function.Consumer;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

/**
 * One heartbeat's work, in order (E1-T7; backlog B15's judgment 1): re-verify the single-instance lock,
 * publish {@code capture_status}, ping the dead-man check, say the summary line. Its steps are ports, so a
 * test drives the order and the wiring; the thread-death backstops beside it in {@code Main} exit the process
 * and stay there.
 */
final class Heartbeat {

    private final @Nullable Runnable lockCheck;
    private final Runnable publish;
    private final @Nullable Runnable ping;
    private final Supplier<String> summary;
    private final Consumer<String> log;

    /** @param lockCheck null when this instance holds no lock (the jsonl sink)
     *  @param ping null when no dead-man check is configured */
    Heartbeat(@Nullable Runnable lockCheck, Runnable publish, @Nullable Runnable ping, Supplier<String> summary,
            Consumer<String> log) {
        this.lockCheck = lockCheck;
        this.publish = publish;
        this.ping = ping;
        this.summary = summary;
        this.log = log;
    }

    void beat() {
        if (lockCheck != null) {
            lockCheck.run();
        }
        publish.run();
        if (ping != null) {
            ping.run();
        }
        log.accept(summary.get());
    }
}
