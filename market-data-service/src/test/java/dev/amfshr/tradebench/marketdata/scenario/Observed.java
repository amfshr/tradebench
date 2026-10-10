package dev.amfshr.tradebench.marketdata.scenario;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.JsonNode;

import dev.amfshr.tradebench.marketdata.events.EventType;
import dev.amfshr.tradebench.marketdata.events.StreamState;

/**
 * The five observables a scenario may assert on — remedies issued on the wire, events recorded,
 * sink writes landed, the heartbeat's rows, how the run ended — and nothing else: never a core's
 * internals. Offsets are from the scenario's start.
 */
public final class Observed {

    /** What the wire saw: {@code connect} (a boot or a rebuild), {@code disconnect} (a rebuild
     * dropping the old session, or the exit hook dropping the last), {@code subscribe} and
     * {@code unsubscribe} of an item. */
    public record Remedy(Duration at, String action, @Nullable String item) {
    }

    /** An event as the log has it, dated by its own wall timestamp — so after a {@code HostSleep}
     * the offset includes the jump, as the row's {@code event_time_utc} would. */
    public record Seen(Duration at, EventType type, @Nullable String epic, @Nullable JsonNode detail) {
    }

    public record Heartbeat(Duration at, String epic, StreamState state, @Nullable Integer dbPending) {
    }

    public record Exit(Duration at, String how) {
    }

    public final List<Remedy> remedies = new ArrayList<>();
    public final List<Seen> events = new ArrayList<>();
    public final List<String> landed = new ArrayList<>();
    public final List<Heartbeat> heartbeats = new ArrayList<>();
    public final List<Exit> exits = new ArrayList<>();
    public final List<String> log = new ArrayList<>();
    public int ticksDelivered;
    public int barsDelivered;
    public long statusFailures;
    /** The scripted store's own counters; in acceptance mode (a real Postgres) they stay 0 — what
     * landed is read back as rows, and that is the claim. */
    public int sinkRecoveries;
    public int ticksHeldAtEnd;
    public String summary = "";

    public List<Remedy> remedies(String action) {
        return remedies.stream().filter(r -> r.action().equals(action)).toList();
    }

    public List<Seen> events(EventType type) {
        return events.stream().filter(e -> e.type() == type).toList();
    }

    public List<Heartbeat> heartbeats(String epic) {
        return heartbeats.stream().filter(h -> h.epic().equals(epic)).toList();
    }

    /** The offsets at which a remedy happened — the shape chapter 10's numbers are asserted in. */
    public List<Long> secondsOf(String action) {
        return remedies(action).stream().map(r -> r.at().toSeconds()).toList();
    }
}
