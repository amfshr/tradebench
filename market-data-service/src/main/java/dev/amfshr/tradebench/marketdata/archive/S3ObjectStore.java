package dev.amfshr.tradebench.marketdata.archive;

import java.net.URI;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HexFormat;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Any bucket that speaks the S3 protocol — Cloudflare R2 for now, chosen at E1-T7 (nod ruling 1).
 * The upload carries the file's SHA-256, so the store verifies the bytes it received and refuses
 * a corrupted upload. Endpoint, bucket and keys are the instance's configuration, never defaults.
 */
public final class S3ObjectStore implements ObjectStore, AutoCloseable {

    private final S3Client s3;
    private final String bucket;

    public S3ObjectStore(URI endpoint, String region, String bucket, String accessKeyId, String secretAccessKey) {
        this.bucket = bucket;
        this.s3 = S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.of(region))
                .forcePathStyle(true) // R2 and MinIO both accept it; no bucket-name DNS needed
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId,
                        secretAccessKey)))
                .httpClient(UrlConnectionHttpClient.builder().build())
                .build();
    }

    @Override
    public void put(String key, Path file, String sha256) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .checksumAlgorithm(ChecksumAlgorithm.SHA256)
                        .checksumSHA256(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256)))
                        .build(),
                RequestBody.fromFile(file));
    }

    @Override
    public void close() {
        s3.close();
    }
}
