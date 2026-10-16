package dev.amfshr.tradebench.marketdata.jobs;

import java.time.Duration;

/**
 * The daily job's timing. {@code settle}: how long after a UTC day ends before it is worked — the
 * last minute's bar seals and IG's history catches up. {@code lookbackDays}: how many complete days
 * the runner considers, so a first boot over old data cannot spend the week's REST allowance (10,000
 * points). A failed phase is retried after {@code retryBase}, doubling to {@code retryCap}.
 */
public record JobTuning(Duration wake, Duration settle, int lookbackDays, Duration retryBase, Duration retryCap) {

    public static JobTuning playbook() {
        return new JobTuning(Duration.ofMinutes(5), Duration.ofMinutes(10), 7, Duration.ofHours(1),
                Duration.ofHours(24));
    }
}
