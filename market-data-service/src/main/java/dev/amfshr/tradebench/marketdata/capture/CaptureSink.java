package dev.amfshr.tradebench.marketdata.capture;

import dev.amfshr.tradebench.core.domain.Bar1m;
import dev.amfshr.tradebench.core.domain.Tick;

/** Where captured events land; T3 writes JSONL, T4 swaps in the database writer. */
public interface CaptureSink extends AutoCloseable {

    void write(Bar1m bar);

    void write(Tick tick);

    void write(CaptureQueues.StateChange stateChange);

    void flush();

    @Override
    void close();
}
