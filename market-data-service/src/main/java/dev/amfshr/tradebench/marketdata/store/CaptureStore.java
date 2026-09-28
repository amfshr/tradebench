package dev.amfshr.tradebench.marketdata.store;

import dev.amfshr.tradebench.core.domain.Bar1m;
import dev.amfshr.tradebench.core.domain.Tick;
import dev.amfshr.tradebench.marketdata.ingest.Buffers;

/** Where captured events land; T3 writes JSONL, T4 swaps in the database writer. */
public interface CaptureStore extends AutoCloseable {

    void write(Bar1m bar);

    void write(Tick tick);

    void write(Buffers.StateChange stateChange);

    void flush();

    @Override
    void close();
}
