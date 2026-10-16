package dev.amfshr.tradebench.marketdata.heal;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import dev.amfshr.tradebench.core.domain.Bar1m;

/** What the heal reads from and writes to the record, for one instance's user and sources. */
public interface HealStore {

    /** The collector's first record of this market, or null if it has none: minutes before it
     * were never claimed, so the heal never asks for them (backfilling history is another job). */
    @Nullable Instant firstRecord(String epic);

    /** The flag in force at {@code at}: the latest change strictly before it, or null if none. */
    @Nullable String flagBefore(String epic, Instant at);

    /** The changes in {@code [from, to)}, oldest first. */
    List<FlagChange> flagChanges(String epic, Instant from, Instant to);

    /** The spans the collector was observing that overlap {@code [from, to)}. */
    List<Window> observed(String epic, Instant from, Instant to);

    /** Minute starts in {@code [from, to)} that hold a bar from any of this user's sources. */
    Set<Instant> barMinutes(String epic, Instant from, Instant to);

    /** Write healed bars under the REST source; idempotent by minute. */
    void writeHealed(List<Bar1m> bars);

    /** Open gap rows whose last minute falls in {@code [from, to)}. */
    List<OpenGap> openGapsEnding(String epic, Instant from, Instant to);

    void closeGap(long id, Instant healedAt, String outcome);

    /** Record a gap the pump never saw, already closed with its outcome. */
    void recordHealedGap(String epic, Run run, Instant healedAt, String outcome);
}
