package dev.amfshr.tradebench.marketdata.scenario;

import java.time.Instant;

import dev.amfshr.tradebench.marketdata.store.CaptureStore;
import dev.amfshr.tradebench.marketdata.store.EventLog;
import dev.amfshr.tradebench.marketdata.store.GapStore;
import dev.amfshr.tradebench.marketdata.store.StatusStore;

/**
 * The stores a run writes through: the scripted fakes the policy scenarios drive (database weather
 * on cue, every write remembered), or a real Postgres in acceptance mode (E1-T12) — the same
 * replay, the real {@code PostgresStore} and observability store, the rows read back as the
 * observables. The runner never knows which.
 */
interface Stores {

    CaptureStore sink();

    EventLog events();

    GapStore gaps();

    StatusStore status();

    /** A fixture's database event ({@code dbDown}, {@code dbUp}, …): scripted stores enact it; a
     * real database cannot be made to misbehave on cue and refuses, loud. */
    void apply(Event event);

    /** What landed and what was recorded, into the observables, once the run is over;
     * {@code start} is the wall instant at offset zero. */
    void collect(Observed observed, Instant start);

    void close();
}
