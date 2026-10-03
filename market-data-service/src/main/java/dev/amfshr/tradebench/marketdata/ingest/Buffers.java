package dev.amfshr.tradebench.marketdata.ingest;

import java.time.Instant;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.jspecify.annotations.Nullable;

import dev.amfshr.tradebench.core.domain.Bar1m;
import dev.amfshr.tradebench.core.domain.Tick;
import dev.amfshr.tradebench.ig.stream.SealedBarUpdate;
import dev.amfshr.tradebench.ig.stream.StreamEvents;
import dev.amfshr.tradebench.ig.stream.TickUpdate;
import dev.amfshr.tradebench.marketdata.supervise.MarketFreshness;

/**
 * The queue pair behind the callback (§2.4): bars unbounded (the must-persist backbone),
 * ticks bounded shed-oldest (freshest wins; backpressure must never reach the socket).
 * Adapts to domain on the LS thread — record allocation is within the cheap-work budget.
 * Also the supervisor's {@link MarketFreshness}: every arrival stamps its market's last-seen
 * monotonic time (O(1)), which the sweep thread pulls — never pushed onto a control queue.
 */
public final class Buffers implements StreamEvents, MarketFreshness {

    public static final int DEFAULT_TICK_CAPACITY = 100_000;

    public record StateChange(String epic, Instant atUtc, String dealFlag) {
    }

    private final LongSupplier monotonicNanos;
    private final LinkedBlockingQueue<Bar1m> bars = new LinkedBlockingQueue<>();
    private final ArrayBlockingQueue<Tick> ticks;
    private final Queue<StateChange> stateChanges = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, String> lastDealFlag = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> tickSeenMono = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> barSeenMono = new ConcurrentHashMap<>();
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
        tickSeenMono.put(update.epic(), monotonicNanos.getAsLong());
        String previous = lastDealFlag.put(update.epic(), update.dealFlag());
        if (!update.dealFlag().equals(previous)) {
            stateChanges.add(new StateChange(update.epic(), update.timestampUtc(),
                    update.dealFlag()));
        }
        Tick tick = StreamAdapter.toDomain(update);
        while (!ticks.offer(tick)) {
            if (ticks.poll() != null) {
                droppedTicks.incrementAndGet();
            }
        }
    }

    @Override
    public void onSealedBar(SealedBarUpdate update) {
        barCount.incrementAndGet();
        barSeenMono.put(update.epic(), monotonicNanos.getAsLong());
        bars.add(StreamAdapter.toDomain(update));
    }

    @Override
    public void onMalformed(String itemName) {
        malformedUpdates.incrementAndGet();
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
        return tickSeenMono.getOrDefault(epic, Long.MIN_VALUE);
    }

    @Override
    public long lastBarMono(String epic) {
        return barSeenMono.getOrDefault(epic, Long.MIN_VALUE);
    }

    @Override
    public @Nullable String dealFlag(String epic) {
        return lastDealFlag.get(epic);
    }
}
