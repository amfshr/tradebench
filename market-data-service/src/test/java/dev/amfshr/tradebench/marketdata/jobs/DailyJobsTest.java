package dev.amfshr.tradebench.marketdata.jobs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.amfshr.tradebench.marketdata.testutil.FakeClock;

/** E1-T6 ②: which market-days are due, the phases in order, retries — against the ruled numbers. */
class DailyJobsTest {

    private static final String DAX = "IX.D.DAX.DAILY.IP";
    private static final LocalDate D = LocalDate.of(2026, 10, 15);
    private static final Instant MIDNIGHT_AFTER_D = Instant.parse("2026-10-16T00:00:00Z");

    private final FakeClock clock = new FakeClock(MIDNIGHT_AFTER_D);
    private final List<String> log = new ArrayList<>();
    private final MemoryStore store = new MemoryStore();
    private final FakePhase heal = new FakePhase("heal");
    private final FakePhase archive = new FakePhase("archive");
    private final DailyJobs jobs = new DailyJobs(clock, List.of(DAX), List.of(heal, archive), store,
            new JobTuning(Duration.ofMinutes(5), Duration.ofMinutes(10), 7, Duration.ofHours(1), Duration.ofHours(4)),
            log::add);

    @Test
    void aDayIsDueExactlyOnceItHasEndedAndSettled() throws Exception {
        clock.advance(Duration.ofMinutes(10).minusNanos(1_000_000));
        jobs.tick();
        assertFalse(heal.ran(D), "9m59.999s after midnight: not yet settled");

        clock.advance(Duration.ofMillis(1));
        jobs.tick();
        assertTrue(heal.ran(D), "10 minutes after the day ends: due");
        assertFalse(heal.ran(D.plusDays(1)), "today is never due");
    }

    @Test
    void thePhasesRunInOrderAndASucceededPhaseIsNotRunAgain() throws Exception {
        settle();
        jobs.tick();
        assertEquals(List.of("heal " + D, "archive " + D), FakePhase.order.subList(FakePhase.order.size() - 2,
                FakePhase.order.size()));
        int runs = store.rows.size();

        clock.advance(Duration.ofMinutes(5));
        jobs.tick();
        assertEquals(runs, store.rows.size(), "both succeeded: nothing more to do for any day");
    }

    @Test
    void theArchiveWaitsForTheHealsAttemptNotItsSuccess() throws Exception {
        heal.failing = true;
        settle();
        jobs.tick();

        assertTrue(archive.ran(D), "a day stuck on a failing heal is still archived");
        assertFalse(store.last("heal", D).succeeded());
    }

    @Test
    void aHealThatSucceedsAfterTheArchiveMakesTheArchiveDueAgain() throws Exception {
        heal.failing = true;
        settle();
        jobs.tick();
        assertEquals(1, archive.count(D));

        heal.failing = false;
        clock.advance(Duration.ofHours(1));
        jobs.tick();

        assertEquals(2, archive.count(D), "the healed bars belong in a fresh archive");
    }

    @Test
    void aFailedPhaseIsRetriedOnADoublingBackoffCappedAtTheTuning() throws Exception {
        heal.failing = true;
        settle();
        List<Long> retriesAtMinutes = new ArrayList<>();
        Instant firstAttempt = clock.wallInstant();
        for (int step = 0; step <= 12 * 15; step++) { // fifteen hours of five-minute wakes, both ends
            int before = heal.count(D);
            jobs.tick();
            if (heal.count(D) > before) {
                retriesAtMinutes.add(Duration.between(firstAttempt, clock.wallInstant()).toMinutes());
            }
            clock.advance(Duration.ofMinutes(5));
        }
        // base 1h, doubling, cap 4h: 0, +1h, +2h, +4h, +4h, +4h
        assertEquals(List.of(0L, 60L, 180L, 420L, 660L, 900L), retriesAtMinutes);
    }

    @Test
    void onlyTheLookbackIsConsidered() throws Exception {
        settle();
        jobs.tick();
        assertTrue(heal.ran(D.minusDays(6)), "seven complete days: D and the six before it");
        assertFalse(heal.ran(D.minusDays(7)), "the eighth is out of reach — the week's allowance is protected");
    }

    @Test
    void aStoreThatFailsEndsTheTickWithoutThrowing() throws Exception {
        settle();
        store.down = true;
        assertDoesNotThrow(jobs::tick);
        assertTrue(log.stream().anyMatch(l -> l.contains("tick abandoned")), "said, never thrown");
    }

    @Test
    void aPhaseThatCannotLoadANativeLibraryIsAFailedRowAndTheRunnerLivesOn() throws Exception {
        // the doctrine review's finding 5: the archive's DuckDB on the wrong platform throws a
        // LinkageError — it must be a failed row, the next day still due, never a dead runner
        DayPhase broken = new DayPhase() {
            @Override
            public String name() {
                return "archive";
            }

            @Override
            public ObjectNode run(MarketDay day) {
                throw new UnsatisfiedLinkError("no libduckdb_java for this platform");
            }
        };
        DailyJobs withBroken = new DailyJobs(clock, List.of(DAX), List.of(heal, broken), store,
                new JobTuning(Duration.ofMinutes(5), Duration.ofMinutes(10), 7, Duration.ofHours(1), Duration.ofHours(4)),
                log::add);
        settle();

        withBroken.tick();

        assertFalse(store.last("archive", D).succeeded(), "the failure is a row");
        assertTrue(store.last("archive", D).detailJson().contains("UnsatisfiedLinkError"));
        assertTrue(heal.ran(D.minusDays(6)), "and every other day was still worked");
    }

    @Test
    void aPhaseInterruptedByAShutdownIsNotRecordedAsAFailure() {
        DayPhase stopped = new DayPhase() {
            @Override
            public String name() {
                return "heal";
            }

            @Override
            public ObjectNode run(MarketDay day) {
                Thread.currentThread().interrupt(); // the shutdown's interrupt, surfacing as a plain exception
                throw new IllegalStateException("retry wait interrupted");
            }
        };
        DailyJobs withStop = new DailyJobs(clock, List.of(DAX), List.of(stopped), store,
                new JobTuning(Duration.ofMinutes(5), Duration.ofMinutes(10), 7, Duration.ofHours(1), Duration.ofHours(4)),
                log::add);
        settle();

        try {
            org.junit.jupiter.api.Assertions.assertThrows(InterruptedException.class, withStop::tick);
            assertTrue(store.rows.isEmpty(), "no failed row for a stop — the next start is not held back an hour");
        } finally {
            Thread.interrupted(); // leave the test thread clean
        }
    }

    private void settle() {
        clock.advance(Duration.ofMinutes(10));
    }

    private static final class FakePhase implements DayPhase {
        static final List<String> order = new ArrayList<>();
        private final String name;
        private final List<LocalDate> days = new ArrayList<>();
        boolean failing;

        FakePhase(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public ObjectNode run(MarketDay day) {
            days.add(day.day());
            order.add(name + " " + day.day());
            if (failing) {
                throw new IllegalStateException(name + " failed");
            }
            return new ObjectMapper().createObjectNode().put("ok", true);
        }

        boolean ran(LocalDate day) {
            return days.contains(day);
        }

        int count(LocalDate day) {
            return (int) days.stream().filter(day::equals).count();
        }
    }

    private static final class MemoryStore implements JobRunStore {
        final List<JobRun> rows = new ArrayList<>();
        boolean down;

        @Override
        public List<JobRun> runs(String job, MarketDay day) {
            if (down) {
                throw new IllegalStateException("database unreachable");
            }
            return rows.stream().filter(r -> r.job().equals(job) && r.day().equals(day)).toList();
        }

        @Override
        public void record(JobRun run) {
            rows.add(run);
        }

        JobRun last(String job, LocalDate day) {
            return rows.stream().filter(r -> r.job().equals(job) && r.day().day().equals(day)).toList().getLast();
        }
    }
}
