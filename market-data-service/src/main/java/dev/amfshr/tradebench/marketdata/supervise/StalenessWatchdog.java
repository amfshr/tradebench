package dev.amfshr.tradebench.marketdata.supervise;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * "A connected socket is not a live feed" (§3.4, ported whole). Pure logic: observations
 * in, {@link Remedy} values out; the supervisor executes them. Market state comes from
 * the stream's own DLG_FLAG — no calendar, no per-market hours config. Host suspend and
 * process freeze are detected by comparing the two clocks and re-baseline every stopwatch
 * so the watchdog never fires INTO a recovery; for the same reason it renders no verdict while
 * the connection itself is down (a retry substate, the handshake, a rebuild in flight — the
 * escalator's domain, E1-T10 #5) and starts every stopwatch afresh when streaming resumes.
 * The CLOSED/SUSPEND stand-down is bounded (D29 (1)): a market that has read a closing flag for
 * {@code standDownTeach} gets one teaching resubscribe per period, whose snapshot re-learns the
 * flag — so a server-side zombie cannot hide behind a weekend.
 */
public final class StalenessWatchdog {

    public enum Signal { TICK_SILENT, BAR_SILENT_TICKS_FLOWING, STAND_DOWN_TEACHING }

    public enum Action { RESUBSCRIBE, REBUILD }

    public record Remedy(String epic, Action action, Signal signal) {
    }

    private static final Set<String> SUPPRESSING_FLAGS = Set.of("CLOSED", "SUSPEND");
    private static final long NEVER = Long.MIN_VALUE;

    private final Tuning tuning;
    private final long sweepNanos;
    private final Map<String, Market> markets = new HashMap<>();
    private long lastRoundMono;
    private long lastRoundWallMillis;
    private boolean baselined;
    private boolean connectionDown;
    private long lastSessionVerdict;
    private long sessionGraceNanos;

    /** {@code sweepInterval} is the shell's cadence, not a tunable: "stale together" (§3.4) means
     * within one sweep of each other (E1-T10 #33). */
    public StalenessWatchdog(Tuning tuning, Duration sweepInterval) {
        this.tuning = tuning;
        this.sweepNanos = sweepInterval.toNanos();
    }

    private static final class Market {
        long lastTick;
        long lastBar;
        @Nullable String dealFlag;
        long stoodDownSince = NEVER;
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

    /** The market's latest DLG_FLAG; a closing flag starts (or continues) its stand-down. */
    public void onDealFlag(String epic, String flag, long monotonicNanos) {
        Market m = markets.get(epic);
        if (m == null) {
            return;
        }
        m.dealFlag = flag;
        if (!SUPPRESSING_FLAGS.contains(flag)) {
            m.stoodDownSince = NEVER;
        } else if (m.stoodDownSince == NEVER) {
            m.stoodDownSince = monotonicNanos;
        }
    }

    /** The market's pair was re-asked for (a resubscribe, a rebuild): the remembered flag is the
     * old subscription's, so it is forgotten until the new one's snapshot re-teaches it (D29 (1)). */
    public void clearDealFlag(String epic) {
        Market m = markets.get(epic);
        if (m != null) {
            m.dealFlag = null;
            m.stoodDownSince = NEVER;
        }
    }

    /** The connection's state: down (any retry substate, the handshake, a rebuild in flight) means
     * no verdicts — silence is the connection's, not a market's; streaming again means every
     * stopwatch and episode starts afresh, so the new session gets its full window. */
    public void onConnection(boolean streaming, long monotonicNanos) {
        if (!streaming) {
            connectionDown = true;
            return;
        }
        if (connectionDown) {
            connectionDown = false;
            rebaseline(monotonicNanos);
            for (Market m : markets.values()) {
                m.episode = null;
            }
            sessionGraceNanos = 0;
        }
    }

    /** One ~1s round. Returns remedies to execute; empty when quiet (= healthy). */
    public List<Remedy> evaluate(long monotonicNanos, long wallMillis) {
        if (clockAnomaly(monotonicNanos, wallMillis)) {
            rebaseline(monotonicNanos);
            return List.of();
        }
        if (connectionDown) {
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
        List<Remedy> remedies = teachingRemedies(monotonicNanos);
        if (stale.size() == 1 && anotherWithinASweep(stale.getFirst().getKey(), monotonicNanos)) {
            return remedies; // hold: next round they are stale together, and that is one verdict
        }
        for (Map.Entry<String, Signal> e : stale) {
            Remedy remedy = remedyFor(markets.get(e.getKey()), e.getKey(), e.getValue(),
                    monotonicNanos);
            if (remedy != null) {
                remedies.add(remedy);
            }
        }
        return remedies;
    }

    /** The bounded stand-down: a market closed for the whole period is re-asked for once, and its
     * silence counts from the fresh subscription — a live item re-teaches its flag within a tick,
     * a dead one is then judged like any open, silent market. */
    private List<Remedy> teachingRemedies(long now) {
        List<Remedy> remedies = new ArrayList<>();
        for (Map.Entry<String, Market> entry : markets.entrySet()) {
            Market m = entry.getValue();
            if (m.stoodDownSince != NEVER && now - m.stoodDownSince >= tuning.standDownTeach().toNanos()) {
                m.stoodDownSince = now; // one per period, whatever comes back
                m.lastTick = now;
                m.lastBar = now;
                remedies.add(new Remedy(entry.getKey(), Action.RESUBSCRIBE, Signal.STAND_DOWN_TEACHING));
            }
        }
        return remedies;
    }

    private boolean anotherWithinASweep(String epic, long now) {
        for (Map.Entry<String, Market> entry : markets.entrySet()) {
            Market m = entry.getValue();
            if (entry.getKey().equals(epic) || isStoodDown(m)) {
                continue;
            }
            if (now - m.lastTick >= tuning.tickSilent().toNanos() - sweepNanos
                    || now - m.lastBar >= tuning.barSilentWhileTicksFlow().toNanos() - sweepNanos) {
                return true;
            }
        }
        return false;
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

    private static boolean isStoodDown(Market m) {
        return m.dealFlag != null && SUPPRESSING_FLAGS.contains(m.dealFlag);
    }

    private @Nullable Signal staleSignal(Market m, long now) {
        if (isStoodDown(m)) {
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
