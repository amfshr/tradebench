package dev.amfshr.tradebench.marketdata.supervise;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Multi-market blast radius (§3.5): one bad market's subscription failure must not
 * rebuild the whole session and wobble the healthy market. The witness rule: a failure
 * while another market's pair is fully SUBSCRIBED means the epic is the only differing
 * variable → market-shaped → quarantine just that market. No witness → session-shaped →
 * rebuild. A strike is one failed <i>attempt</i> — a pair is two legs, and the second leg's
 * rejection (or a straggler from a pair already replaced) is not a second strike (E1-T10 #8).
 * A verdict due while a would-be witness is still inside its confirm window {@code Wait}s,
 * keeping its strike, and is rendered by {@link #rejudge} as soon as that witness confirms or
 * its window lapses (E1-T10 #4). Quarantine exit is restart-only: config-shaped failures don't
 * self-heal — and a quarantined market's later rejections are {@code Ignored}: never a fresh
 * strike, never re-admitted (E1-T10 #1).
 */
public final class WitnessQuarantine {

    public enum Kind { PRICE, CHART }

    public sealed interface Judgment {
        record Retry(String epic) implements Judgment { }

        /** A would-be witness is still inside its confirm window — held; {@link #rejudge} renders it. */
        record Wait() implements Judgment { }

        record Quarantine(String epic) implements Judgment { }

        record Rebuild(String epic) implements Judgment { }

        /** Nothing to judge: the market is quarantined, or this rejection is a leg of an attempt
         * already struck or already replaced; the shell does nothing with it. */
        record Ignored(String epic) implements Judgment { }
    }

    private final Tuning tuning;
    private final Map<String, Market> markets = new HashMap<>();
    private final Set<String> quarantined = new HashSet<>();

    public WitnessQuarantine(Tuning tuning) {
        this.tuning = tuning;
    }

    private static final class Market {
        long subscribeStartedMono;
        final Set<Kind> confirmed = new HashSet<>();
        int strikes;
        long struckAttempt = Long.MIN_VALUE;
        boolean verdictPending;
    }

    public void onSubscribeStarted(String epic, long monotonicNanos) {
        Market m = markets.computeIfAbsent(epic, e -> new Market());
        m.subscribeStartedMono = monotonicNanos;
        m.confirmed.clear();
    }

    public void onSubscribed(String epic, Kind kind) {
        Market m = markets.get(epic);
        if (m != null) {
            m.confirmed.add(kind);
        }
    }

    /**
     * Judge a subscription failure stamped {@code monotonicNanos} on the callback thread. Strikes
     * are per-session attempt counts, not exact-string matches — a flapping rejection code must
     * not launder the count — and one attempt strikes once, however many legs the server refuses.
     */
    public Judgment onSubscriptionError(String epic, long monotonicNanos) {
        if (quarantined.contains(epic)) {
            return new Judgment.Ignored(epic); // the twin leg's rejection arrives after the verdict
        }
        Market failing = markets.computeIfAbsent(epic, e -> new Market());
        if (monotonicNanos < failing.subscribeStartedMono) {
            return new Judgment.Ignored(epic); // a leg of a pair already replaced
        }
        if (failing.strikes > 0 && failing.struckAttempt == failing.subscribeStartedMono) {
            return new Judgment.Ignored(epic); // this attempt has struck — the pair's other leg
        }
        failing.strikes++;
        failing.struckAttempt = failing.subscribeStartedMono;
        if (failing.strikes < tuning.subscriptionStrikes()) {
            return new Judgment.Retry(epic);
        }
        return verdict(epic, failing, monotonicNanos);
    }

    /** Render every held verdict whose witness has now confirmed or whose window has lapsed. */
    public List<Judgment> rejudge(long monotonicNanos) {
        List<String> pending = new ArrayList<>();
        for (Map.Entry<String, Market> entry : markets.entrySet()) {
            if (entry.getValue().verdictPending) {
                pending.add(entry.getKey());
            }
        }
        List<Judgment> rendered = new ArrayList<>();
        for (String epic : pending) {
            Judgment judgment = verdict(epic, markets.get(epic), monotonicNanos);
            if (!(judgment instanceof Judgment.Wait)) {
                rendered.add(judgment);
            }
        }
        return rendered;
    }

    private Judgment verdict(String epic, Market failing, long monotonicNanos) {
        boolean pendingWitness = false;
        for (Map.Entry<String, Market> entry : markets.entrySet()) {
            if (entry.getKey().equals(epic) || quarantined.contains(entry.getKey())) {
                continue;
            }
            Market other = entry.getValue();
            if (other.confirmed.size() == Kind.values().length) {
                quarantined.add(epic);
                markets.remove(epic);
                return new Judgment.Quarantine(epic);
            }
            if (monotonicNanos - other.subscribeStartedMono
                    < tuning.subscribeConfirmWindow().toNanos()) {
                pendingWitness = true;
            }
        }
        if (pendingWitness) {
            // A would-be witness is still inside its confirm window: wait rather than
            // race a fast rejection into a whole-service rebuild (§3.5).
            failing.verdictPending = true;
            return new Judgment.Wait();
        }
        failing.verdictPending = false;
        return new Judgment.Rebuild(epic);
    }

    /** A refused unsubscribe would double-deliver every update — always rebuild. */
    public Judgment onUnsubscribeRefused(String epic) {
        return new Judgment.Rebuild(epic);
    }

    /** Session rebuilt: strikes and held verdicts reset; quarantine persists (exit is restart-only). */
    public void onSessionRebuilt() {
        markets.clear();
    }

    public Set<String> quarantined() {
        return Set.copyOf(quarantined);
    }
}
