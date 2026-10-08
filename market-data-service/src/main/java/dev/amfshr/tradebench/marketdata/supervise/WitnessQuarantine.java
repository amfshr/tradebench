package dev.amfshr.tradebench.marketdata.supervise;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Multi-market blast radius (§3.5): one bad market's subscription failure must not
 * rebuild the whole session and wobble the healthy market. The witness rule: a failure
 * while another market's pair is fully SUBSCRIBED means the epic is the only differing
 * variable → market-shaped → quarantine just that market. No witness → session-shaped →
 * rebuild. Quarantine exit is restart-only: config-shaped failures don't self-heal — and a
 * quarantined market's later rejections (the pair's second leg, a late one) are {@code Ignored}:
 * never a fresh strike, never re-admitted (E1-T10 #1).
 */
public final class WitnessQuarantine {

    public enum Kind { PRICE, CHART }

    public sealed interface Judgment {
        record Retry(String epic) implements Judgment { }

        record Wait() implements Judgment { }

        record Quarantine(String epic) implements Judgment { }

        record Rebuild() implements Judgment { }

        /** Already quarantined — nothing to judge; the shell does nothing with it. */
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
     * Judge a subscription failure. Strikes are per-session attempt counts, not
     * exact-string matches — a flapping rejection code must not launder the count.
     */
    public Judgment onSubscriptionError(String epic, long monotonicNanos) {
        if (quarantined.contains(epic)) {
            return new Judgment.Ignored(epic); // the twin leg's rejection arrives after the verdict
        }
        Market failing = markets.computeIfAbsent(epic, e -> new Market());
        failing.strikes++;
        if (failing.strikes < tuning.subscriptionStrikes()) {
            return new Judgment.Retry(epic);
        }
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
            failing.strikes--;
            return new Judgment.Wait();
        }
        return new Judgment.Rebuild();
    }

    /** A refused unsubscribe would double-deliver every update — always rebuild. */
    public Judgment onUnsubscribeRefused(String epic) {
        return new Judgment.Rebuild();
    }

    /** Session rebuilt: strikes reset; quarantine persists (exit is restart-only). */
    public void onSessionRebuilt() {
        markets.clear();
    }

    public Set<String> quarantined() {
        return Set.copyOf(quarantined);
    }
}
