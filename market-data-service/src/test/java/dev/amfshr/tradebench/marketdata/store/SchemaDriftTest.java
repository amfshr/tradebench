package dev.amfshr.tradebench.marketdata.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * The drift gate (triage D23): the live schema (Flyway applied to a fresh Postgres) must
 * byte-match the committed snapshot. On failure the actual render is written to
 * build/schema-actual.sql — inspect the diff, and only if the change is intended copy it
 * over src/test/resources/schema/expected-schema.sql.
 */
class SchemaDriftTest extends PostgresTestBase {

    @Test
    void liveSchemaMatchesTheCommittedSnapshot() throws Exception {
        String actual = normalize(dumpSchema());
        Path actualFile = Path.of("build/schema-actual.sql");
        Files.createDirectories(actualFile.getParent());
        Files.writeString(actualFile, actual);

        assertEquals(normalize(expectedSnapshot()), actual,
                "schema drifted — diff " + actualFile + " against"
                        + " src/test/resources/schema/expected-schema.sql");
    }

    private String dumpSchema() throws IOException, InterruptedException {
        var result = postgres.execInContainer("pg_dump", "--schema-only", "--no-owner",
                "--no-privileges", "-U", postgres.getUsername(),
                postgres.getDatabaseName());
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("pg_dump failed: " + result.getStderr());
        }
        return result.getStdout();
    }

    private static String expectedSnapshot() throws IOException {
        try (InputStream in = SchemaDriftTest.class
                .getResourceAsStream("/schema/expected-schema.sql")) {
            if (in == null) {
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String normalize(String dump) {
        return dump.lines()
                .filter(line -> !line.startsWith("--"))
                .filter(line -> !line.startsWith("\\restrict"))
                .filter(line -> !line.startsWith("\\unrestrict"))
                .filter(line -> !line.startsWith("SET "))
                .filter(line -> !line.startsWith("SELECT pg_catalog"))
                .filter(line -> !line.isBlank())
                .collect(Collectors.joining("\n"));
    }
}
