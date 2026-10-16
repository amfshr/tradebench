package dev.amfshr.tradebench.marketdata.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.amfshr.tradebench.core.domain.OhlcPrices;
import dev.amfshr.tradebench.marketdata.jobs.MarketDay;

/** E1-T6 ④: a market-day becomes two objects, each named by a manifest row written only once it landed. */
class ArchiverTest {

    private static final String DAX = "IX.D.DAX.DAILY.IP";
    private static final MarketDay DAX_D = new MarketDay(DAX, LocalDate.of(2026, 10, 15));
    private static final String PREFIX = "platform/ig-stream-demo/IX.D.DAX.DAILY.IP/2026-10-15";

    private final MemoryStore store = new MemoryStore();
    private final RecordingBucket bucket = new RecordingBucket();
    private final Archiver archiver = new Archiver(store, bucket, "platform/ig-stream-demo");

    @Test
    void aDayBecomesItsTwoObjectsAndAManifestRowEach() throws Exception {
        store.ticks.add(new ArchivedTick(Instant.parse("2026-10-15T07:00:00Z"), BigDecimal.ONE, BigDecimal.TWO, "ig-stream-demo"));
        store.bars.add(bar("2026-10-15T07:00:00Z", "ig-stream-demo"));
        store.bars.add(bar("2026-10-15T07:01:00Z", "ig-rest-demo"));

        ObjectNode detail = archiver.run(DAX_D);

        assertEquals(List.of(PREFIX + "/ticks.parquet", PREFIX + "/bars.parquet"), new ArrayList<>(bucket.objects.keySet()));
        assertEquals(List.of("parquet_ticks_day " + PREFIX + "/ticks.parquet", "parquet_bars_day " + PREFIX + "/bars.parquet"),
                store.rows.stream().map(r -> r.kind + " " + r.key).toList());
        for (Row row : store.rows) {
            byte[] landed = bucket.objects.get(row.key);
            assertEquals(landed.length, row.bytes, "the row's size is the object's");
            assertEquals(sha256(landed), row.sha256, "the row's digest is the object's");
            assertEquals(row.sha256, bucket.digests.get(row.key), "the digest sent with the upload is the same");
        }
        assertEquals(2, detail.get("archived").asInt());
    }

    @Test
    void aDayWithNothingRecordedArchivesNothingAndSaysSo() throws Exception {
        ObjectNode detail = archiver.run(DAX_D);

        assertTrue(bucket.objects.isEmpty());
        assertTrue(store.rows.isEmpty());
        assertEquals(0, detail.get("archived").asInt());
    }

    @Test
    void anUploadThatFailsWritesNoRow() {
        store.bars.add(bar("2026-10-15T07:00:00Z", "ig-stream-demo"));
        bucket.failing = true;

        assertThrows(IOException.class, () -> archiver.run(DAX_D));

        assertTrue(store.rows.isEmpty(), "a row always names an object that exists");
    }

    @Test
    void theWorkingFilesAreRemovedWhateverHappened() throws Exception {
        store.bars.add(bar("2026-10-15T07:00:00Z", "ig-stream-demo"));
        archiver.run(DAX_D);
        bucket.failing = true;
        assertThrows(IOException.class, () -> archiver.run(DAX_D));

        for (Path file : bucket.paths) {
            assertFalse(Files.exists(file), "no working file left behind: " + file);
            assertFalse(Files.exists(file.getParent()), "nor its directory");
        }
    }

    private static ArchivedBar bar(String iso, String source) {
        OhlcPrices p = new OhlcPrices(BigDecimal.ONE, BigDecimal.TWO, BigDecimal.ONE, BigDecimal.TWO);
        return new ArchivedBar(Instant.parse(iso), p, p, null, source);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record Row(String kind, String key, long bytes, String sha256) {
    }

    private static final class MemoryStore implements ArchiveStore {
        final List<ArchivedTick> ticks = new ArrayList<>();
        final List<ArchivedBar> bars = new ArrayList<>();
        final List<Row> rows = new ArrayList<>();

        @Override
        public List<ArchivedTick> ticks(String epic, Instant from, Instant to) {
            return ticks;
        }

        @Override
        public List<ArchivedBar> bars(String epic, Instant from, Instant to) {
            return bars;
        }

        @Override
        public void record(String epic, LocalDate day, String kind, String objectKey, long bytes, String sha256) {
            rows.add(new Row(kind, objectKey, bytes, sha256));
        }
    }

    private static final class RecordingBucket implements ObjectStore {
        final Map<String, byte[]> objects = new LinkedHashMap<>();
        final Map<String, String> digests = new LinkedHashMap<>();
        final List<Path> paths = new ArrayList<>();
        boolean failing;

        @Override
        public void put(String key, Path file, String sha256) throws IOException {
            paths.add(file);
            if (failing) {
                throw new IOException("bucket unreachable");
            }
            objects.put(key, Files.readAllBytes(file));
            digests.put(key, sha256);
        }
    }
}
