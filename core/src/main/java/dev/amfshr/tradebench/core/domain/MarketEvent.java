package dev.amfshr.tradebench.core.domain;

import java.time.Instant;

/**
 * One market event. Sealed: the engine dispatches exhaustively over exactly these shapes
 * ("one engine, many clocks" — the same consumer runs live, in replay, and in backtest;
 * the ordered event-stream SPI itself is designed at E3).
 *
 * <p>{@link #dataTime()} is the event's <em>data</em> timestamp — decisions are made on
 * data time, never on arrival time (prototype D38 doctrine).
 */
public sealed interface MarketEvent permits Tick, Bar1m {

    String epic();

    Instant dataTime();
}
