package dev.amfshr.tradebench.marketdata.ingest;

import java.time.Instant;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

import org.jspecify.annotations.Nullable;

import dev.amfshr.tradebench.core.domain.Bar1m;
import dev.amfshr.tradebench.core.domain.Tick;
import dev.amfshr.tradebench.ig.stream.SealedBarUpdate;
import dev.amfshr.tradebench.ig.stream.StreamEvents;
import dev.amfshr.tradebench.ig.stream.TickUpdate;
import dev.amfshr.tradebench.marketdata.supervise.MarketTelemetry;

/**
 * The queue pair behind the callback (§2.4): bars unbounded (the must-persist backbone),
 * ticks bounded shed-oldest (freshest wins; backpressure must never reach the socket).
 * Adapts to domain on the LS thread — record allocation is within the cheap-work budget.
 * Also the per-market telemetry the belt and the heartbeat read: every arrival stamps its
 * market's last-seen monotonic time (the watchdog's pull, O(1)) and data time and bumps its
 * counts — read by other threads, never pushed onto a control queue.
 */
public final class Buffers implements StreamEvents, MarketTelemetry {

    public static final int DEFAULT_TICK_CAPACITY = 100_000;

    public record StateChange(String epic, Instant atUtc, String dealFlag) {
    }

    /** One market's running telemetry — adders and volatile stamps, written on the LS thread. */
    private static final class Market {
        final LongAdder ticks = new LongAdder();
        final LongAdder bars = new LongAdder();
        final LongAdder dropped = new LongAdder();
        final LongAdder malformed = new LongAdder();
        volatile long lastTickMono = Long.MIN_VALUE;
        volatile long lastBarMono = Long.MIN_VALUE;
        volatile @Nullable Instant lastTickAt;
        volatile @Nullable Instant lastBarAt;
    }

    private final LongSupplier monotonicNanos;
    private final LinkedBlockingQueue<Bar1m> bars = new LinkedBlockingQueue<>();
    private final ArrayBlockingQueue<Tick> ticks;
    private final Queue<StateChange> stateChanges = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, String> lastDealFlag = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Market> markets = new ConcurrentHashMap<>();
    private final AtomicLong droppedTicks = new AtomicLong();
    private final AtomicLong malformedUpdates = new AtomicLong();
    private final AtomicLong tickCount = new AtomicLong();
    private final AtomicLong barCount = new AtomicLong();

    public Buffers(int tickCapacity, LongSupplier monotonicNanos) {
        this.ticks = new ArrayBlockingQueue<>(tickCapacity);
        this.monotonicNanos = monotonicNanos;
    }

    @Override
    public void onTick(TickUpdate update) {
        tickCount.incrementAndGet();
        Market market = market(update.epic());
        market.ticks.increment();
        market.lastTickMono = monotonicNanos.getAsLong();
        market.lastTickAt = update.timestampUtc();
        String previous = lastDealFlag.put(update.epic(), update.dealFlag());
        if (!update.dealFlag().equals(previous)) {
            stateChanges.add(new StateChange(update.epic(), update.timestampUtc(),
                    update.dealFlag()));
        }
        Tick tick = StreamAdapter.toDomain(update);
        while (!ticks.offer(tick)) {
            Tick shed = ticks.poll();
            if (shed != null) {
                droppedTicks.incrementAndGet();
                market(shed.epic()).dropped.increment(); // the shed tick's market pays, not the arriving one
            }
        }
    }

    @Override
    public void onSealedBar(SealedBarUpdate update) {
        barCount.incrementAndGet();
        Market market = market(update.epic());
        market.bars.increment();
        market.lastBarMono = monotonicNanos.getAsLong();
        market.lastBarAt = update.startUtc();
        bars.add(StreamAdapter.toDomain(update));
    }

    @Override
    public void onMalformed(String itemName) {
        malformedUpdates.incrementAndGet();
        String epic = epicOf(itemName);
        if (epic != null) {
            market(epic).malformed.increment();
        }
    }

    // LS item names carry the market: PRICE:<account>:<epic> and CHART:<epic>:1MINUTE (chapter 1).
    static @Nullable String epicOf(String itemName) {
        String[] parts = itemName.split(":");
        if (parts.length >= 3 && parts[0].equals("PRICE")) {
            return parts[2];
        }
        if (parts.length >= 3 && parts[0].equals("CHART")) {
            return parts[1];
        }
        return null;
    }

    private Market market(String epic) {
        return markets.computeIfAbsent(epic, k -> new Market());
    }

    public @Nullable Bar1m peekBarNow() {
        return bars.peek();
    }

    public void removeBarNow() {
        bars.poll();
    }

    public @Nullable Tick pollTickNow() {
        return ticks.poll();
    }

    public @Nullable StateChange peekStateChangeNow() {
        return stateChanges.peek();
    }

    public void removeStateChangeNow() {
        stateChanges.poll();
    }

    public long droppedTicks() {
        return droppedTicks.get();
    }

    public long malformedUpdates() {
        return malformedUpdates.get();
    }

    public long tickCount() {
        return tickCount.get();
    }

    public long barCount() {
        return barCount.get();
    }

    @Override
    public long lastTickMono(String epic) {
        Market market = markets.get(epic);
        return market == null ? Long.MIN_VALUE : market.lastTickMono;
    }

    @Override
    public long lastBarMono(String epic) {
        Market market = markets.get(epic);
        return market == null ? Long.MIN_VALUE : market.lastBarMono;
    }

    @Override
    public @Nullable String dealFlag(String epic) {
        return lastDealFlag.get(epic);
    }

    @Override
    public MarketCounts countsFor(String epic) {
        Market market = markets.get(epic);
        if (market == null) {
            return MarketCounts.NONE;
        }
        return new MarketCounts(market.ticks.sum(), market.bars.sum(), market.dropped.sum(),
                market.malformed.sum(), market.lastTickAt, market.lastBarAt);
    }

    @Override
    public int pendingWrites() {
        return bars.size() + ticks.size();
    }
}
