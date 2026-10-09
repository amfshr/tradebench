package dev.amfshr.tradebench.marketdata.testutil;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import dev.amfshr.tradebench.core.domain.Bar1m;
import dev.amfshr.tradebench.core.domain.Tick;
import dev.amfshr.tradebench.marketdata.store.CaptureStore;
import dev.amfshr.tradebench.marketdata.store.PersistenceException;

/**
 * A {@link CaptureStore} with {@code PostgresStore}'s failure semantics and none of its SQL: bars
 * land on write, ticks batch and land on flush, a tick is held before anything can fail, every
 * write and flush is refused after a failure until {@link #recover()}, {@link #down} makes every
 * write fail retryably — Postgres is away — for as long as a scenario says, {@link #rejectWrites}
 * refuses writes — and a recovery holding ticks, which lands them as its round trip — and {@link #terminal} makes the next write fail for a reason no retry will fix.
 */
public final class ScriptedCaptureStore implements CaptureStore {

    /** Connection lost mid-statement — the blip taxonomy (D28). */
    private static final String BLIP = "08006";
    /** Undefined table — nothing a retry can fix. */
    private static final String SCHEMA = "42P01";
    /** Disk full — retryable by the taxonomy, invisible to a recovery that only reconnects. */
    private static final String DISK_FULL = "53100";
    /** {@code PostgresStore.TICK_BATCH_LIMIT}: a full batch lands without waiting for an idle flush. */
    private static final int TICK_BATCH_LIMIT = 500;

    /** What landed, in order: {@code bar@<startUtc seconds>} / {@code tick@<timestamp millis>}. */
    public final List<String> landed = new ArrayList<>();
    public boolean down;
    public boolean terminal;
    public boolean rejectWrites;
    public int recoveries;
    public int failures;

    private final List<Tick> pending = new ArrayList<>();
    private boolean broken;

    @Override
    public void write(Bar1m bar) {
        refuseIfBroken();
        failIfScripted("bar write failed");
        landed.add("bar@" + bar.startUtc().getEpochSecond());
    }

    @Override
    public void write(Tick tick) {
        pending.add(tick); // held before anything can fail, as the real store does
        refuseIfBroken();
        failIfScripted("tick write failed");
        if (pending.size() >= TICK_BATCH_LIMIT) {
            flush();
        }
    }

    @Override
    public void flush() {
        if (pending.isEmpty()) {
            return;
        }
        refuseIfBroken();
        failIfScripted("tick batch flush failed");
        for (Tick tick : pending) {
            landed.add("tick@" + tick.timestamp().toEpochMilli());
        }
        pending.clear();
    }

    @Override
    public void recover() {
        recoveries++;
        if (down) {
            fail("sink recovery failed — still unavailable", BLIP);
        }
        if (!pending.isEmpty()) { // the held batch is the round trip: it lands, or nothing recovered
            if (rejectWrites) {
                fail("sink recovery failed — the held batch was refused", DISK_FULL);
            }
            for (Tick tick : pending) {
                landed.add("tick@" + tick.timestamp().toEpochMilli());
            }
            pending.clear();
        }
        broken = false;
    }

    @Override
    public void close() {
        flush();
    }

    /** Ticks held but not yet landed. */
    public int held() {
        return pending.size();
    }

    private void refuseIfBroken() {
        if (broken) {
            throw new PersistenceException("sink is broken since an earlier failure — recover() first",
                    new SQLException("refused", BLIP));
        }
    }

    private void failIfScripted(String what) {
        if (terminal) {
            fail(what, SCHEMA);
        }
        if (down) {
            fail(what, BLIP);
        }
        if (rejectWrites) {
            fail(what, DISK_FULL);
        }
    }

    private void fail(String what, String sqlState) {
        failures++;
        broken = true;
        throw new PersistenceException(what, new SQLException(what, sqlState));
    }
}
