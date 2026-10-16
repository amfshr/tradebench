package dev.amfshr.tradebench.marketdata.jobs;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.amfshr.tradebench.core.time.Clock;

/**
 * Which market-days are due, and the phases run on them in order (E1-T6). Driven by the data, not
 * a configured time: a UTC day is due once it has ended and settled, and a phase is due until it
 * has succeeded. A later phase waits until the one before it has been attempted, and is due again
 * whenever the one before it succeeds after it did — so a day stuck on a failing heal is still
 * archived, and a heal that finally works gets its bars into a fresh archive. A failed phase is
 * retried with a doubling backoff. Nothing here can stop the collector: a phase's failure is a
 * failed row, a store's failure ends the tick.
 */
public final class DailyJobs {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Clock clock;
    private final List<String> epics;
    private final List<DayPhase> phases;
    private final JobRunStore store;
    private final JobTuning tuning;
    private final Consumer<String> log;

    public DailyJobs(Clock clock, List<String> epics, List<DayPhase> phases, JobRunStore store, JobTuning tuning,
            Consumer<String> log) {
        this.clock = clock;
        this.epics = List.copyOf(epics);
        this.phases = List.copyOf(phases);
        this.store = store;
        this.tuning = tuning;
        this.log = log;
    }

    /** One wake: every due phase of every due market-day, oldest day first. */
    public void tick() throws InterruptedException {
        Instant now = clock.wallInstant();
        LocalDate lastComplete = now.minus(tuning.settle()).atOffset(ZoneOffset.UTC).toLocalDate().minusDays(1);
        LocalDate first = lastComplete.minusDays(tuning.lookbackDays() - 1L);
        try {
            for (LocalDate day = first; !day.isAfter(lastComplete); day = day.plusDays(1)) {
                for (String epic : epics) {
                    runDay(new MarketDay(epic, day));
                }
            }
        } catch (RuntimeException storeDown) {
            log.accept("jobs: tick abandoned — " + storeDown + "; next wake tries again");
        }
    }

    private void runDay(MarketDay day) throws InterruptedException {
        @Nullable Instant upstreamSuccess = null;
        for (int i = 0; i < phases.size(); i++) {
            DayPhase phase = phases.get(i);
            List<JobRun> runs = store.runs(phase.name(), day);
            @Nullable Instant lastSuccess = latestSuccess(runs);
            boolean stale = lastSuccess == null
                    || (upstreamSuccess != null && lastSuccess.isBefore(upstreamSuccess));
            if (stale && retryDue(runs)) {
                JobRun run = attempt(phase, day);
                store.record(run);
                if (run.succeeded()) {
                    lastSuccess = run.startedUtc();
                }
                runs = List.of(run); // attempted, whatever its outcome
            }
            if (runs.isEmpty()) {
                return; // never attempted: the phases after it wait
            }
            upstreamSuccess = lastSuccess;
        }
    }

    private JobRun attempt(DayPhase phase, MarketDay day) throws InterruptedException {
        Instant started = clock.wallInstant();
        try {
            ObjectNode detail = phase.run(day);
            return new JobRun(phase.name(), day, started, clock.wallInstant(), true, detail.toString());
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception | LinkageError e) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("stopped during " + phase.name()); // a shutdown, not a failure
            }
            // a LinkageError too — a native library that will not load (the archive's DuckDB on the wrong
            // platform) must be a failed row and the next day still due, never a dead runner
            log.accept("jobs: " + phase.name() + " " + day.epic() + " " + day.day() + " failed — " + e);
            ObjectNode detail = MAPPER.createObjectNode().put("error", String.valueOf(e));
            return new JobRun(phase.name(), day, started, clock.wallInstant(), false, detail.toString());
        }
    }

    /** Due unless the trailing failures' backoff has not elapsed: 1×, 2×, 4× … the base, capped. */
    private boolean retryDue(List<JobRun> runs) {
        int failures = 0;
        for (int i = runs.size() - 1; i >= 0 && !runs.get(i).succeeded(); i--) {
            failures++;
        }
        if (failures == 0) {
            return true;
        }
        long factor = 1L << Math.min(failures - 1, 30);
        Duration wait = tuning.retryBase().multipliedBy(factor);
        if (wait.compareTo(tuning.retryCap()) > 0) {
            wait = tuning.retryCap();
        }
        return !clock.wallInstant().isBefore(runs.getLast().startedUtc().plus(wait));
    }

    private static @Nullable Instant latestSuccess(List<JobRun> runs) {
        @Nullable Instant latest = null;
        for (JobRun run : runs) {
            if (run.succeeded() && (latest == null || run.startedUtc().isAfter(latest))) {
                latest = run.startedUtc();
            }
        }
        return latest;
    }
}
