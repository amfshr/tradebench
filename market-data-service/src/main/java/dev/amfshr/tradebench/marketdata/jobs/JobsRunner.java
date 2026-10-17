package dev.amfshr.tradebench.marketdata.jobs;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** The daily job's thread, {@code capture-jobs}: a tick every {@code wake}. A scheduled task that
 * throws is never run again, so every tick is caught; an {@link Error} is said loud first. */
public final class JobsRunner {

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "capture-jobs"));
    private final DailyJobs jobs;
    private final Duration wake;
    private final Consumer<String> log;

    public JobsRunner(DailyJobs jobs, Duration wake, Consumer<String> log) {
        this.jobs = jobs;
        this.wake = wake;
        this.log = log;
    }

    public void start() {
        executor.scheduleWithFixedDelay(this::tick, wake.toMillis(), wake.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void tick() {
        try {
            jobs.tick();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.accept("jobs: tick failed — " + e + "; next wake tries again");
        } catch (Error e) {
            log.accept("FATAL: jobs runner died — the daily heal and archive are void from here: " + e);
            throw e;
        }
    }

    /** Stop scheduling and interrupt a phase in flight; true if it ended within {@code wait}. */
    public boolean stop(Duration wait) throws InterruptedException {
        executor.shutdownNow();
        return executor.awaitTermination(wait.toMillis(), TimeUnit.MILLISECONDS);
    }
}
