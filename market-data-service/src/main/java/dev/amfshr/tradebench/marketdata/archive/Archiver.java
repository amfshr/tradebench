package dev.amfshr.tradebench.marketdata.archive;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.amfshr.tradebench.marketdata.jobs.DayPhase;
import dev.amfshr.tradebench.marketdata.jobs.MarketDay;

/**
 * The archive (E1-T6): a market's UTC day as two Parquet files — its ticks and its bars, streamed
 * and healed — put off the box, then a manifest row each. The row is written only after the object
 * landed, so a row always names an object that exists; a re-run replaces both. A day with nothing
 * recorded archives nothing and says so.
 */
public final class Archiver implements DayPhase {

    public static final String NAME = "archive";
    static final String TICKS = "parquet_ticks_day";
    static final String BARS = "parquet_bars_day";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ArchiveStore store;
    private final ObjectStore bucket;
    private final String keyPrefix;

    /** @param keyPrefix the owner and source the objects sit under, e.g. {@code platform/ig-stream-demo} */
    public Archiver(ArchiveStore store, ObjectStore bucket, String keyPrefix) {
        this.store = store;
        this.bucket = bucket;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ObjectNode run(MarketDay day) throws Exception {
        Instant from = day.day().atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = from.plusSeconds(86_400);
        List<ArchivedTick> ticks = store.ticks(day.epic(), from, to);
        List<ArchivedBar> bars = store.bars(day.epic(), from, to);
        ObjectNode detail = MAPPER.createObjectNode().put("ticks", ticks.size()).put("bars", bars.size());
        if (ticks.isEmpty() && bars.isEmpty()) {
            return detail.put("archived", 0).put("reason", "nothing recorded for this market-day");
        }
        Path dir = Files.createTempDirectory("archive-");
        try {
            Path ticksFile = dir.resolve("ticks.parquet");
            Path barsFile = dir.resolve("bars.parquet");
            DayParquet.writeTicks(ticksFile, ticks);
            DayParquet.writeBars(barsFile, bars);
            long bytes = put(day, TICKS, "ticks.parquet", ticksFile) + put(day, BARS, "bars.parquet", barsFile);
            return detail.put("archived", 2).put("bytes", bytes).put("prefix", prefix(day));
        } finally {
            try (var files = Files.list(dir)) {
                for (Path f : files.toList()) {
                    Files.deleteIfExists(f);
                }
            }
            Files.deleteIfExists(dir);
        }
    }

    private long put(MarketDay day, String kind, String name, Path file) throws Exception {
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        String key = prefix(day) + "/" + name;
        long bytes = Files.size(file);
        bucket.put(key, file, sha256);
        store.record(day.epic(), day.day(), kind, key, bytes, sha256); // only once the object landed
        return bytes;
    }

    private String prefix(MarketDay day) {
        return keyPrefix + "/" + day.epic() + "/" + day.day();
    }
}
