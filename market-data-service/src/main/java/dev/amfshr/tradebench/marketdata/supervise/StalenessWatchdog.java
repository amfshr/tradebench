package dev.amfshr.tradebench.marketdata.supervise;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * "A connected socket is not a live feed" (§3.4, ported whole). Pure logic: observations
 * in, {@link Remedy} values out; the supervisor executes them. Market state comes from
 * the stream's own DLG_FLAG — no calendar, no per-market hours config. Host sleep and
 * process freeze are detected by comparing the two clocks and re-baseline every stopwatch
 * so the watchdog never fires INTO a recovery.
 */
public final class StalenessWatchdog {

    public enum Signal { TICK_SILENT, BAR_SILENT_TICKS_FLOWING }

    public enum Action { RESUBSCRIBE, REBUILD }

    public record Remedy(String epic, Action action, Signal signal) {
    }

    private static final Set<String> SUPPRESSING_FLAGS = Set.of("CLOSED", "SUSPEND");

    private final Tuning tuning;
    private final Map<String, Market> markets = new HashMap<>();
    private long lastRoundMono;
    private long lastRoundWallMillis;
    private boolean baselined;
    private long lastSessionVerdict;
    private long sessionGraceNanos;

    public StalenessWatchdog(Tuning tuning) {
        this.tuning = tuning;
    }

    private static final class Market {
        long lastTick;
        long lastBar;
        @Nullable String dealFlag;
        @Nullable Episode episode;
    }

    private static final class Episode {
        Signal signal;
        int resubscribesUsed;
        long lastRemedy;
        long graceNanos;

        Episode(Signal signal) {
            this.signal = signal;
        }
    }

    public void track(String epic, long monotonicNanos) {
        markets.computeIfAbsent(epic, e -> {
            Market m = new Market();
            m.lastTick = monotonicNanos;
            m.lastBar = monotonicNanos;
            return m;
        });
    }

    public void forget(String epic) {
        markets.remove(epic);
    }

    public void onTick(String epic, long monotonicNanos) {
        Market m = markets.get(epic);
        if (m == null) {
            return;
        }
        m.lastTick = monotonicNanos;
        healIf(m, Signal.TICK_SILENT);
    }

    public void onSealedBar(String epic, long monotonicNanos) {
        Market m = markets.get(epic);
        if (m == null) {
            return;
        }
        m.lastBar = monotonicNanos;
        healIf(m, Signal.BAR_SILENT_TICKS_FLOWING);
    }

    public void onDealFlag(String epic, String flag) {
        Market m = markets.get(epic);
        if (m != null) {
            m.dealFlag = flag;
        }
    }

    /** One ~1s round. Returns remedies to execute; empty when quiet (= healthy). */
    public List<Remedy> evaluate(long monotonicNanos, long wallMillis) {
        if (clockAnomaly(monotonicNanos, wallMillis)) {
            rebaseline(monotonicNanos);
            return List.of();
        }
        List<Map.Entry<String, Signal>> stale = new ArrayList<>();
        for (Map.Entry<String, Market> entry : markets.entrySet()) {
            Signal signal = staleSignal(entry.getValue(), monotonicNanos);
            if (signal != null) {
                stale.add(Map.entry(entry.getKey(), signal));
            }
        }
        if (stale.size() >= 2) {
            // Session-shaped: several markets stale together is never market noise (§3.4).
            // Same storm-guard as per-market episodes: verdicts don't re-fire every round.
            if (sessionGraceNanos == 0) {
                sessionGraceNanos = tuning.watchdogGraceBase().toNanos();
            } else if (monotonicNanos - lastSessionVerdict < sessionGraceNanos) {
                return List.of();
            }
            lastSessionVerdict = monotonicNanos;
            sessionGraceNanos = Math.min(sessionGraceNanos * 2,
                    tuning.watchdogGraceCap().toNanos());
            return List.of(new Remedy(stale.getFirst().getKey(), Action.REBUILD,
                    stale.getFirst().getValue()));
        }
        sessionGraceNanos = 0;
        List<Remedy> remedies = new ArrayList<>();
        for (Map.Entry<String, Signal> e : stale) {
            Remedy remedy = remedyFor(markets.get(e.getKey()), e.getKey(), e.getValue(),
                    monotonicNanos);
            if (remedy != null) {
                remedies.add(remedy);
            }
        }
        return remedies;
    }

    private boolean clockAnomaly(long monotonicNanos, long wallMillis) {
        if (!baselined) {
            lastRoundMono = monotonicNanos;
            lastRoundWallMillis = wallMillis;
            baselined = true;
            return false;
        }
        long monoDeltaMs = (monotonicNanos - lastRoundMono) / 1_000_000;
        long wallDeltaMs = wallMillis - lastRoundWallMillis;
        lastRoundMono = monotonicNanos;
        lastRoundWallMillis = wallMillis;
        boolean hostSlept = wallDeltaMs - monoDeltaMs > tuning.hostSleepSkew().toMillis();
        boolean froze = monoDeltaMs > tuning.processFreezeJump().toMillis();
        return hostSlept || froze;
    }

    private void rebaseline(long monotonicNanos) {
        for (Market m : markets.values()) {
            m.lastTick = monotonicNanos;
            m.lastBar = monotonicNanos;
        }
    }

    private @Nullable Signal staleSignal(Market m, long now) {
        if (m.dealFlag != null && SUPPRESSING_FLAGS.contains(m.dealFlag)) {
            return null;
        }
        boolean tickSilent = now - m.lastTick >= tuning.tickSilent().toNanos();
        if (tickSilent) {
            return Signal.TICK_SILENT;
        }
        if (now - m.lastBar >= tuning.barSilentWhileTicksFlow().toNanos()) {
            // Ticks flowing (tick-silent didn't fire) yet no completed bar: the only way
            // to see a dead CHART subscription while PRICE lives.
            return Signal.BAR_SILENT_TICKS_FLOWING;
        }
        return null;
    }

    private @Nullable Remedy remedyFor(Market m, String epic, Signal signal, long now) {
        Episode episode = m.episode;
        if (episode == null || episode.signal != signal) {
            episode = new Episode(signal);
            episode.graceNanos = tuning.watchdogGraceBase().toNanos();
            m.episode = episode;
        } else if (now - episode.lastRemedy < episode.graceNanos) {
            return null;
        }
        episode.lastRemedy = now;
        episode.graceNanos = Math.min(episode.graceNanos * 2,
                tuning.watchdogGraceCap().toNanos());
        if (episode.resubscribesUsed < tuning.watchdogMaxResubscribes()) {
            episode.resubscribesUsed++;
            return new Remedy(epic, Action.RESUBSCRIBE, signal);
        }
        return new Remedy(epic, Action.REBUILD, signal);
    }

    private static void healIf(Market m, Signal kind) {
        // An episode resets ONLY on healing evidence of its own signal kind — "any item
        // heals" would let flowing ticks endlessly re-arm a dead-CHART episode.
        if (m.episode != null && m.episode.signal == kind) {
            m.episode = null;
        }
    }
}
