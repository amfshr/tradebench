package dev.amfshr.tradebench.marketdata.store;

import dev.amfshr.tradebench.core.domain.Bar1m;
import dev.amfshr.tradebench.core.domain.Tick;

/**
 * Where captured market data lands; T3 writes JSONL, T4 swaps in the database writer. Market
 * data only (decision #1) — service events and bar gaps go to the observability seams, never
 * here.
 */
public interface CaptureStore extends AutoCloseable {

    void write(Bar1m bar);

    void write(Tick tick);

    void flush();

    @Override
    void close();
}
