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
 * write fail retryably — Postgres is away — for as long as a scenario says, and {@link #terminal}
 * makes the next one fail for a reason no retry will fix.
 */
public final class ScriptedCaptureStore implements CaptureStore {

    /** Connection lost mid-statement — the blip taxonomy (D28). */
    private static final String BLIP = "08006";
    /** Undefined table — nothing a retry can fix. */
    private static final String SCHEMA = "42P01";

    /** What landed, in order: {@code bar@<startUtc seconds>} / {@code tick@<timestamp millis>}. */
    public final List<String> landed = new ArrayList<>();
    public boolean down;
    public boolean terminal;
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
    }

    private void fail(String what, String sqlState) {
        failures++;
        broken = true;
        throw new PersistenceException(what, new SQLException(what, sqlState));
    }
}
