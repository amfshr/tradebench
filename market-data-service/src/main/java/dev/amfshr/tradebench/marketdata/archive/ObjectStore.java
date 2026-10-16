package dev.amfshr.tradebench.marketdata.archive;

import java.nio.file.Path;

/** Where an archived file goes: a bucket over the S3 protocol in production (E1-T6 nod ruling 1). */
public interface ObjectStore {

    /** Put {@code file} at {@code key}, replacing whatever was there; fails unless the bytes landed. */
    void put(String key, Path file, String sha256) throws Exception;
}
