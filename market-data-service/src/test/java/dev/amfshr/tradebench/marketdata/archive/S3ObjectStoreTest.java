package dev.amfshr.tradebench.marketdata.archive;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/** E1-T6 ④: the upload against a real S3-protocol server (Adobe's S3Mock — MinIO no longer publishes images)
 * — no cloud account needed to build or test. */
class S3ObjectStoreTest {

    private static final String BUCKET = "archive-test";
    private static GenericContainer<?> s3;
    private static URI endpoint;
    private static S3Client reader;

    @TempDir
    Path dir;

    @BeforeAll
    static void start() {
        s3 = new GenericContainer<>("adobe/s3mock:5.2.3").withExposedPorts(9090)
                .waitingFor(Wait.forHttp("/").forPort(9090).forStatusCodeMatching(code -> code < 500));
        s3.start();
        endpoint = URI.create("http://" + s3.getHost() + ":" + s3.getMappedPort(9090));
        reader = S3Client.builder().endpointOverride(endpoint).region(Region.of("auto"))
                .forcePathStyle(true).httpClient(UrlConnectionHttpClient.builder().build())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build();
        reader.createBucket(b -> b.bucket(BUCKET));
    }

    @AfterAll
    static void stop() {
        reader.close();
        s3.stop();
    }

    @Test
    void aFileLandsAtItsKeyByteForByte() throws Exception {
        Path file = Files.writeString(dir.resolve("bars.parquet"), "the day's bars");
        try (S3ObjectStore store = store()) {
            store.put("platform/ig-stream-demo/IX.D.DAX.DAILY.IP/2026-10-15/bars.parquet", file, sha256(file));
        }

        byte[] landed = reader.getObjectAsBytes(GetObjectRequest.builder().bucket(BUCKET)
                .key("platform/ig-stream-demo/IX.D.DAX.DAILY.IP/2026-10-15/bars.parquet").build()).asByteArray();
        assertArrayEquals(Files.readAllBytes(file), landed);
    }

    @Test
    void theFilesDigestTravelsWithTheUpload() throws Exception {
        // Amazon S3 and R2 verify x-amz-checksum-sha256 against the bytes received and refuse a
        // mismatch; S3Mock neither checks nor keeps it. So the wire itself is read here: a recording
        // server stands in for the bucket, and the digest must arrive with the bytes.
        Path file = Files.writeString(dir.resolve("ticks.parquet"), "the day's ticks");
        String digest = sha256(file);
        List<String> seen = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String header = exchange.getRequestHeaders().getFirst("x-amz-checksum-sha256");
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
            seen.add(header != null ? header : body.contains("x-amz-checksum-sha256:") // a trailer, if chunked
                    ? body.replaceAll("(?s).*x-amz-checksum-sha256:([A-Za-z0-9+/=]+).*", "$1") : "none");
            exchange.getResponseHeaders().add("ETag", "\"e\"");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try (S3ObjectStore store = new S3ObjectStore(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "auto", BUCKET, "test", "test")) {
            store.put("platform/ticks.parquet", file, digest);
        } finally {
            server.stop(0);
        }

        assertEquals(List.of(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(digest))), seen);
    }

    private static S3ObjectStore store() {
        return new S3ObjectStore(endpoint, "auto", BUCKET, "test", "test");
    }

    static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
