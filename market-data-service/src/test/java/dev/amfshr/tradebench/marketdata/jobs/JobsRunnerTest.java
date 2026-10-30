package dev.amfshr.tradebench.marketdata.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.amfshr.tradebench.core.time.Clock;
import dev.amfshr.tradebench.marketdata.events.LogLine;
import dev.amfshr.tradebench.marketdata.events.Severity;

/** The jobs thread's one rule: a tick that throws is said, as a warning, and the next wake tries again. */
class JobsRunnerTest {

    @Test
    void aTickThatThrowsIsAWarningAndTheRunnerLivesOn() {
        Clock broken = new Clock() {
            @Override
            public Instant wallInstant() {
                throw new IllegalStateException("the clock broke");
            }

            @Override
            public long monotonicNanos() {
                return 0;
            }
        };
        List<String> log = new ArrayList<>();
        JobsRunner runner = new JobsRunner(new DailyJobs(broken, List.of("IX.D.DAX.DAILY.IP"), List.of(), new JobRunStore() {
            @Override
            public List<JobRun> runs(String job, MarketDay day) {
                return List.of();
            }

            @Override
            public void record(JobRun run) {
            }
        }, new JobTuning(Duration.ofMinutes(5), Duration.ofMinutes(10), 7, Duration.ofHours(1), Duration.ofHours(4)),
                log::add), Duration.ofMinutes(5), log::add);

        runner.tick();

        assertEquals(1, log.size(), log.toString());
        assertEquals(Severity.WARN, LogLine.severityOf(log.getFirst()), log.getFirst());
        assertTrue(LogLine.text(log.getFirst()).startsWith("jobs: tick failed — java.lang.IllegalStateException: the clock broke"));
    }
}
