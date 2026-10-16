package dev.amfshr.tradebench.marketdata.archive;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** What the archive reads from the record and writes to its manifest, for one instance's user. */
public interface ArchiveStore {

    /** The streamed ticks in {@code [from, to)}, oldest first. */
    List<ArchivedTick> ticks(String epic, Instant from, Instant to);

    /** The bars in {@code [from, to)} from every source — streamed and healed — oldest first. */
    List<ArchivedBar> bars(String epic, Instant from, Instant to);

    /** The manifest row for one object; a re-run replaces it (one per market-day per kind). */
    void record(String epic, LocalDate day, String kind, String objectKey, long bytes, String sha256);
}
